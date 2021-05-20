package beam.agentsim.infrastructure

import akka.actor.Status.Failure
import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.agents.vehicles.{BeamVehicle, VehicleManager}
import beam.agentsim.events.{ChargingPlugInEvent, ChargingPlugOutEvent, RefuelSessionEvent}
import beam.agentsim.infrastructure.ChargingNetwork.{ChargingCycle, ChargingStation, ChargingVehicle, ConnectionStatus}
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.{ParkingType, PricingModel}
import beam.agentsim.infrastructure.power.SitePowerManager.PhysicalBounds
import beam.agentsim.infrastructure.power.{PowerController, SitePowerManager}
import beam.agentsim.infrastructure.taz.TAZ
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.cosim.helics.BeamHelicsInterface.{getFederate, unloadHelics, BeamFederate}
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import beam.utils.DateUtils
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.NonFatal

/**
  * Created by haitamlaarabi
  */

class ChargingNetworkManager(
  beamServices: BeamServices,
  chargingNetworkInfo: ChargingNetworkInfo,
  parkingManager: ActorRef,
  scheduler: ActorRef
) extends Actor
    with ActorLogging {
  import ChargingNetworkManager._
  import ConnectionStatus._
  import beamServices._
  import chargingNetworkInfo._

  private val beamConfig: BeamConfig = beamScenario.beamConfig
  private val cnmConfig = beamConfig.beam.agentsim.chargingNetworkManager

  private val chargingNetworkMap: Map[Id[VehicleManager], ChargingNetwork] = Map(
    VehicleManager.privateVehicleManager.managerId ->
    new ChargingNetwork(VehicleManager.privateVehicleManager.managerId, chargingZoneList)
  )
  private val sitePowerManager = new SitePowerManager(chargingNetworkMap, beamServices)
  private val powerController = new PowerController(chargingNetworkMap, beamConfig, beamFederateOption)
  private val endOfSimulationTime: Int = DateUtils.getEndOfTime(beamConfig)

  import powerController._
  import sitePowerManager._

  import scala.concurrent.ExecutionContext.Implicits.global
  import scala.concurrent.Future
  implicit val timeout: Timeout = Timeout(10, TimeUnit.HOURS)

  private def currentTimeBin(tick: Int): Int = cnmConfig.timeStepInSeconds * (tick / cnmConfig.timeStepInSeconds).toInt
  private def nextTimeBin(tick: Int): Int = currentTimeBin(tick) + cnmConfig.timeStepInSeconds

  var timeSpentToPlanEnergyDispatchTrigger: Long = 0
  var nHandledPlanEnergyDispatchTrigger: Int = 0

  val maybeDebugReport: Option[Cancellable] = if (beamServices.beamConfig.beam.debug.debugEnabled) {
    Some(context.system.scheduler.scheduleWithFixedDelay(10.seconds, 30.seconds, self, DebugReport)(context.dispatcher))
  } else {
    None
  }

  override def postStop: Unit = {
    maybeDebugReport.foreach(_.cancel())
    log.info(
      s"timeSpentToPlanEnergyDispatchTrigger: ${timeSpentToPlanEnergyDispatchTrigger} ms, nHandledPlanEnergyDispatchTrigger: ${nHandledPlanEnergyDispatchTrigger}, AVG: ${timeSpentToPlanEnergyDispatchTrigger.toDouble / nHandledPlanEnergyDispatchTrigger}"
    )
    super.postStop()
  }

  override def receive: Receive = {
    case DebugReport =>
      log.info(
        s"timeSpentToPlanEnergyDispatchTrigger: ${timeSpentToPlanEnergyDispatchTrigger} ms, nHandledPlanEnergyDispatchTrigger: ${nHandledPlanEnergyDispatchTrigger}, AVG: ${timeSpentToPlanEnergyDispatchTrigger.toDouble / nHandledPlanEnergyDispatchTrigger}"
      )

    case TriggerWithId(InitializeTrigger(_), triggerId) =>
      Future(scheduler ? ScheduleTrigger(PlanEnergyDispatchTrigger(0), self))
        .map(_ => CompletionNotice(triggerId, Vector()))
        .pipeTo(sender())

    case TriggerWithId(PlanEnergyDispatchTrigger(timeBin), triggerId) =>
      val s = System.currentTimeMillis
      log.debug(s"Planning energy dispatch for vehicles currently connected to a charging point, at t=$timeBin")
      val estimatedLoad = requiredPowerInKWOverNextPlanningHorizon(timeBin)
      log.debug("Total Load estimated is {} at tick {}", estimatedLoad.values.sum, timeBin)

      // obtaining physical bounds
      val physicalBounds = obtainPowerPhysicalBounds(timeBin, Some(estimatedLoad))

      val allConnectedVehicles = chargingNetworkMap.flatMap(_._2.connectedVehicles)
      val triggers = allConnectedVehicles.par.flatMap {
        case (_, chargingVehicle @ ChargingVehicle(vehicle, stall, _, _, _, _, _, _)) =>
          // Refuel
          handleRefueling(chargingVehicle)
          // Calculate the energy to charge each vehicle connected to the a charging station
          val (chargingDuration, energyToCharge) =
            dispatchEnergy(cnmConfig.timeStepInSeconds, chargingVehicle, physicalBounds)
          if (chargingDuration > 0 && energyToCharge == 0) {
            log.error(
              s"chargingDuration is $chargingDuration while energyToCharge is $energyToCharge. Something is broken or due to physical bounds!!"
            )
          } else if (chargingDuration == 0 && energyToCharge > 0) {
            log.error(
              s"chargingDuration is $chargingDuration while energyToCharge is $energyToCharge. Something is broken!!"
            )
          }
          // update charging vehicle with dispatched energy and schedule ChargingTimeOutScheduleTrigger
          chargingVehicle.processChargingCycle(timeBin, energyToCharge, chargingDuration).flatMap {
            case cycle if chargingIsCompleteUsing(cycle) =>
              handleEndCharging(timeBin, chargingVehicle)
              None
            case cycle if chargingNotCompleteUsing(cycle) =>
              log.debug(
                "Ending refuel cycle of vehicle {}. Stall: {}. Provided energy: {} J. Remaining: {} J",
                vehicle.id,
                stall,
                cycle.energy,
                energyToCharge
              )
              None
            case cycle =>
              // charging is going to end during this current session
              Some(
                ScheduleTrigger(
                  ChargingTimeOutTrigger(timeBin + cycle.duration, vehicle.id, stall.managerId),
                  self
                )
              )
          }
      }

      val nextStepPlanningTriggers = if (!isEndOfSimulation(timeBin)) {
        Vector(ScheduleTrigger(PlanEnergyDispatchTrigger(nextTimeBin(timeBin)), self))
      } else {
        completeChargingAndTheDisconnectionOfAllConnectedVehiclesAtEndOfSimulation(timeBin, physicalBounds)
      }

      val e = System.currentTimeMillis()
      nHandledPlanEnergyDispatchTrigger += 1
      timeSpentToPlanEnergyDispatchTrigger += e - s
      log.info(s"timeSpentToPlanEnergyDispatchTrigger: $timeSpentToPlanEnergyDispatchTrigger. tick: $timeBin")

      sender ! CompletionNotice(triggerId, triggers.toIndexedSeq ++ nextStepPlanningTriggers)

    case TriggerWithId(ChargingTimeOutTrigger(tick, vehicleId, vehicleManager), triggerId) =>
      log.debug(s"ChargingTimeOutTrigger for vehicle $vehicleId at $tick")
      val chargingNetwork = chargingNetworkMap(vehicleManager)
      chargingNetwork.lookupVehicle(vehicleId) match { // not taking into consideration vehicles waiting in line
        case Some(chargingVehicle) =>
          handleEndCharging(tick, chargingVehicle) // We don't inform Actors that a private vehicle has ended charging because we don't know which agent should be informed
        case _ => log.debug(s"Vehicle $vehicleId is already disconnected")
      }
      sender ! CompletionNotice(triggerId)

    case ChargingPlugRequest(tick, vehicle, vehicleManager) =>
      log.debug(s"ChargingPlugRequest received for vehicle $vehicle at $tick and stall ${vehicle.stall}")
      if (vehicle.isBEV | vehicle.isPHEV) {
        val chargingNetwork = chargingNetworkMap(vehicleManager)
        // connecting the current vehicle
        chargingNetwork.attemptToConnectVehicle(tick, vehicle, sender) match {
          case Some(ChargingVehicle(vehicle, _, station, _, _, _, status, _)) if status.last == Waiting =>
            log.debug(
              s"Vehicle $vehicle is moved to waiting line at $tick in station $station, with {}/{} vehicles connected and {} in waiting line",
              station.connectedVehicles.size,
              station.zone.numChargers,
              station.waitingLineVehicles.size
            )
            log.debug(s"connected vehicles: ${station.connectedVehicles.keys.mkString(",")}")
            log.debug(s"waiting vehicles: ${station.waitingLineVehicles.keys.mkString(",")}")
            sender ! WaitingInLine(tick, vehicle.id)
          case Some(chargingVehicle: ChargingVehicle) =>
            handleStartCharging(tick, chargingVehicle)
          case _ =>
            log.debug(s"attempt to connect vehicle ${vehicle.id} to charger failed!")
        }
      } else {
        sender ! Failure(
          new RuntimeException(
            s"$vehicle is not a BEV/PHEV vehicle. Request sent by agent ${sender.path.name}"
          )
        )
      }

    case ChargingUnplugRequest(tick, vehicle, vehicleManager) =>
      log.debug(s"ChargingUnplugRequest received for vehicle $vehicle from plug ${vehicle.stall} at $tick")
      val physicalBounds = obtainPowerPhysicalBounds(tick, None)
      val chargingNetwork = chargingNetworkMap(vehicleManager)
      chargingNetwork.lookupVehicle(vehicle.id) match { // not taking into consideration vehicles waiting in line
        case Some(chargingVehicle) =>
          val prevStartTime = chargingVehicle.chargingSessions.last.startTime
          val startTime = Math.min(tick, prevStartTime)
          val endTime = Math.max(tick, prevStartTime)
          val duration = endTime - startTime
          val (chargeDurationAtTick, energyToChargeAtTick) = dispatchEnergy(duration, chargingVehicle, physicalBounds)
          chargingVehicle.processChargingCycle(startTime, energyToChargeAtTick, chargeDurationAtTick)
          handleEndCharging(tick, chargingVehicle, Some(sender))
        case _ =>
          log.debug(s"Vehicle $vehicle is already disconnected at $tick")
          sender ! UnhandledVehicle(tick, vehicle.id)
      }

    case Finish =>
      log.info("CNM is Finishing. Now clearing the charging networks!")
      chargingNetworkMap.foreach(_._2.clearAllMappedStations())
      disconnectFromHELICS()
      context.children.foreach(_ ! Finish)
      context.stop(self)
  }

  /**
    * if charging completed then duration of charging should be zero
    * @param cycle the latest charging cycle
    * @return
    */
  private def chargingIsCompleteUsing(cycle: ChargingCycle) = cycle.duration == 0

  /**
    * if charging won't complete during the current cycle
    * @param cycle the latest charging cycle
    * @return
    */
  private def chargingNotCompleteUsing(cycle: ChargingCycle) = cycle.duration >= cnmConfig.timeStepInSeconds

  /**
    * if this is the last timebin of the simulation
    * @param tick current tick
    * @return a boolean
    */
  private def isEndOfSimulation(tick: Int) = nextTimeBin(tick) >= endOfSimulationTime

  /**
    * if we still have a BEV/PHEV that is connected to a charging point,
    * we assume that they will charge until the end of the simulation and throwing events accordingly
    * @param tick current time bin
    * @param physicalBounds physical bounds received from the grid (or default ones if not)
    * @return triggers to schedule
    */
  private def completeChargingAndTheDisconnectionOfAllConnectedVehiclesAtEndOfSimulation(
    tick: Int,
    physicalBounds: Map[ChargingStation, PhysicalBounds]
  ): Vector[ScheduleTrigger] = {
    chargingNetworkMap
      .flatMap(_._2.vehicles)
      .map {
        case (_, chargingVehicle @ ChargingVehicle(vehicle, stall, _, _, _, _, status, _)) =>
          if (status.last == Connected) {
            val (duration, energy) =
              dispatchEnergy(Int.MaxValue, chargingVehicle, physicalBounds)
            chargingVehicle.processChargingCycle(tick, energy, duration)
          }
          ScheduleTrigger(
            ChargingTimeOutTrigger(
              nextTimeBin(tick) - 1,
              vehicle.id,
              stall.managerId
            ),
            self
          )
      }
      .toVector
  }

  /**
    * Connect the vehicle
    * @param tick current time
    * @param chargingVehicle charging vehicle information
    */
  private def handleStartCharging(tick: Int, chargingVehicle: ChargingVehicle): Unit = {
    val ChargingVehicle(vehicle, stall, _, _, _, theSender, _, _) = chargingVehicle
    log.debug(s"Starting charging for vehicle $vehicle at $tick")
    val physicalBounds = obtainPowerPhysicalBounds(tick, None)
    vehicle.connectToChargingPoint(tick)
    theSender ! StartingRefuelSession(tick, vehicle.id)
    handleStartChargingHelper(tick, chargingVehicle)
    val (chargeDurationAtTick, energyToChargeAtTick) =
      dispatchEnergy(nextTimeBin(tick) - tick, chargingVehicle, physicalBounds)
    chargingVehicle.processChargingCycle(tick, energyToChargeAtTick, chargeDurationAtTick)
    val endTime = chargingVehicle.computeSessionEndTime
    if (endTime < nextTimeBin(tick))
      scheduler ! ScheduleTrigger(ChargingTimeOutTrigger(endTime, vehicle.id, stall.managerId), self)
  }

  /**
    * Disconnect the vehicle
    * @param tick current time
    * @param chargingVehicle charging vehicle information
    * @param currentSenderMaybe the current actor who sent the message
    */
  private def handleEndCharging(
    tick: Int,
    chargingVehicle: ChargingVehicle,
    currentSenderMaybe: Option[ActorRef] = None
  ): Unit = {
    val ChargingVehicle(vehicle, stall, _, _, _, _, _, _) = chargingVehicle
    val chargingNetwork = chargingNetworkMap(stall.managerId)
    chargingNetwork.disconnectVehicle(chargingVehicle) match {
      case Some(cv) =>
        log.debug(s"Vehicle ${chargingVehicle.vehicle} has been disconnected from the charging station")
        handleRefueling(chargingVehicle)
        handleEndChargingHelper(tick, chargingVehicle)
        vehicle.disconnectFromChargingPoint()
        vehicle.stall match {
          case Some(stall) =>
            parkingManager ! ReleaseParkingStall(stall)
            vehicle.unsetParkingStall()
          case None =>
            log.error(
              s"Vehicle ${vehicle.id.toString} does not have a stall. ParkingManager won't get ReleaseParkingStall event."
            )
        }
        currentSenderMaybe.foreach(_ ! EndingRefuelSession(tick, vehicle.id))
        chargingNetwork.processWaitingLine(tick, cv.chargingStation).foreach(handleStartCharging(tick, _))
      case None =>
        log.error(
          s"Vehicle ${chargingVehicle.vehicle} failed to disconnect. Check the debug logs if it has been already disconnected. Otherwise something is broken!!"
        )
    }
  }

  /**
    * Refuel the vehicle using last charging session and collect the corresponding load
    * @param chargingVehicle vehicle charging information
    */
  private def handleRefueling(chargingVehicle: ChargingVehicle): Unit = {
    chargingVehicle.latestChargingCycle.foreach {
      case ChargingCycle(startTime, energy, duration) =>
        collectObservedLoadInKW(startTime, duration, chargingVehicle.vehicle, chargingVehicle.chargingStation)
        chargingVehicle.vehicle.addFuel(energy)
        log.debug(
          s"Charging vehicle ${chargingVehicle.vehicle}. " +
          s"Stall ${chargingVehicle.vehicle.stall}. Provided energy of = $energy J"
        )
    }
  }

  /**
    * process the event ChargingPlugInEvent
    * @param currentTick current time
    * @param chargingVehicle vehicle charging information
    */
  private def handleStartChargingHelper(currentTick: Int, chargingVehicle: ChargingVehicle): Unit = {
    import chargingVehicle._
    val chargingPlugInEvent = new ChargingPlugInEvent(
      tick = currentTick,
      stall = stall,
      locationWGS = geo.utm2Wgs(stall.locationUTM),
      vehId = vehicle.id,
      primaryFuelLevel = vehicle.primaryFuelLevelInJoules,
      secondaryFuelLevel = Some(vehicle.secondaryFuelLevelInJoules)
    )
    log.debug(s"ChargingPlugInEvent: $chargingPlugInEvent")
    beamServices.matsimServices.getEvents.processEvent(chargingPlugInEvent)
  }

  /**
    * Calculates the duration of the refuel session, the provided energy and throws corresponding events
    * @param currentTick current time
    * @param chargingVehicle vehicle charging information
    */
  def handleEndChargingHelper(currentTick: Int, chargingVehicle: ChargingVehicle): Unit = {
    import chargingVehicle._
    log.debug(
      s"Vehicle $vehicle was disconnected at time {} with {} J delivered during {} sec",
      currentTick,
      chargingVehicle.computeSessionEnergy,
      chargingVehicle.computeSessionDuration
    )
    // Refuel Session
    val refuelSessionEvent = new RefuelSessionEvent(
      currentTick,
      stall.copy(locationUTM = geo.utm2Wgs(stall.locationUTM)),
      chargingVehicle.computeSessionEnergy,
      vehicle.primaryFuelLevelInJoules - chargingVehicle.computeSessionEnergy,
      chargingVehicle.computeSessionDuration,
      vehicle.id,
      vehicle.beamVehicleType
    )
    log.debug(s"RefuelSessionEvent: $refuelSessionEvent")
    beamServices.matsimServices.getEvents.processEvent(refuelSessionEvent)
    val chargingPlugOutEvent: ChargingPlugOutEvent = new ChargingPlugOutEvent(
      currentTick,
      stall.copy(locationUTM = geo.utm2Wgs(stall.locationUTM)),
      vehicle.id,
      vehicle.primaryFuelLevelInJoules,
      Some(vehicle.secondaryFuelLevelInJoules)
    )
    log.debug(s"ChargingPlugOutEvent: $chargingPlugOutEvent")
    beamServices.matsimServices.getEvents.processEvent(chargingPlugOutEvent)
  }
}

object ChargingNetworkManager extends LazyLogging {
  var beamFederateOption: Option[BeamFederate] = None
  object DebugReport
  case class ChargingZonesInquiry()
  case class PlanEnergyDispatchTrigger(tick: Int) extends Trigger
  case class ChargingTimeOutTrigger(tick: Int, vehicleId: Id[BeamVehicle], managerId: Id[VehicleManager])
      extends Trigger
  case class ChargingPlugRequest(tick: Int, vehicle: BeamVehicle, managerId: Id[VehicleManager])
  case class ChargingUnplugRequest(tick: Int, vehicle: BeamVehicle, managerId: Id[VehicleManager])
  case class StartingRefuelSession(tick: Int, vehicleId: Id[BeamVehicle])
  case class EndingRefuelSession(tick: Int, vehicleId: Id[BeamVehicle])
  case class WaitingInLine(tick: Int, vehicleId: Id[BeamVehicle])
  case class UnhandledVehicle(tick: Int, vehicleId: Id[BeamVehicle])

  final case class ChargingZone(
    tazId: Id[TAZ],
    parkingType: ParkingType,
    numChargers: Int,
    chargingPointType: ChargingPointType,
    pricingModel: PricingModel,
    managerId: Id[VehicleManager]
  ) {
    val id: String = constructChargingZoneKey(managerId, tazId, parkingType, chargingPointType)
    override def equals(that: Any): Boolean =
      that match {
        case that: ChargingZone =>
          that.hashCode() == hashCode
        case _ => false
      }
    override def hashCode: Int = id.hashCode()
  }

  def constructChargingZoneKey(
    managerId: Id[VehicleManager],
    tazId: Id[TAZ],
    parkingType: ParkingType,
    chargingPointType: ChargingPointType
  ): String = s"cs_${managerId}_${tazId}_${parkingType}_${chargingPointType}"

  def props(
    beamServices: BeamServices,
    chargingNetworkInfo: ChargingNetworkInfo,
    parkingManager: ActorRef,
    scheduler: ActorRef
  ): Props = {
    Props(new ChargingNetworkManager(beamServices, chargingNetworkInfo, parkingManager, scheduler))
  }

  def connectToHELICS(beamConfig: BeamConfig): Boolean = {
    val helicsConfig = beamConfig.beam.agentsim.chargingNetworkManager.helics
    beamFederateOption = if (helicsConfig.connectionEnabled) {
      var counter = 1
      var fed = attemptConnectionToHELICS(beamConfig)
      while (fed.isEmpty && counter <= 3) {
        logger.error(s"Failed to connect to helics network. Connection attempt in 60 seconds [$counter/3]")
        Thread.sleep(60000)
        fed = attemptConnectionToHELICS(beamConfig)
        counter += 1
      }
      fed
    } else None
    !helicsConfig.connectionEnabled || beamFederateOption.isDefined
  }

  def disconnectFromHELICS(): Unit = {

    /**
      * closing helics connection
      */
    logger.debug("Release Helics resources...")
    beamFederateOption
      .fold(logger.debug("Not connected to grid, just releasing helics resources")) { beamFederate =>
        beamFederate.close()
        try {
          logger.debug("Destroying BeamFederate")
          unloadHelics()
        } catch {
          case NonFatal(ex) =>
            logger.error(s"Cannot destroy BeamFederate: ${ex.getMessage}")
        }
      }
  }

  private def attemptConnectionToHELICS(beamConfig: BeamConfig): Option[BeamFederate] = {
    import scala.util.{Failure, Try}
    val helicsConfig = beamConfig.beam.agentsim.chargingNetworkManager.helics
    logger.info("Connecting to helics network for CNM...")
    Try {
      logger.debug("Init BeamFederate...")
      getFederate(
        helicsConfig.federateName,
        helicsConfig.coreType,
        helicsConfig.coreInitString,
        helicsConfig.timeDeltaProperty,
        helicsConfig.intLogLevel,
        helicsConfig.bufferSize,
        helicsConfig.dataOutStreamPoint match {
          case s: String if s.nonEmpty => Some(s)
          case _                       => None
        },
        helicsConfig.dataInStreamPoint match {
          case s: String if s.nonEmpty => Some(s)
          case _                       => None
        }
      )
    }.recoverWith {
      case e =>
        logger.warn("Cannot init BeamFederate: {} as connection to helics failed", e.getMessage)
        Failure(e)
    }.toOption
  }
}
