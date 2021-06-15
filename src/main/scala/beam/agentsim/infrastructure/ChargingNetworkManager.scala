package beam.agentsim.infrastructure

import akka.actor.Status.Failure
import akka.actor.{ActorLogging, ActorRef, Cancellable, Props}
import akka.pattern.pipe
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
import beam.agentsim.scheduler.{HasTriggerId, Trigger}
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import beam.sim.config.BeamConfig.Beam.Debug
import beam.utils.DateUtils
import beam.utils.logging.LoggingMessageActor
import beam.utils.logging.pattern.ask
import org.matsim.api.core.v01.Id

import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * Created by haitamlaarabi
  */

class ChargingNetworkManager(
  beamServices: BeamServices,
  chargingInfrastructure: ParkingAndChargingInfrastructure,
  parkingManager: ActorRef,
  scheduler: ActorRef
) extends LoggingMessageActor
    with ActorLogging {
  import ChargingNetworkManager._
  import ConnectionStatus._
  import beamServices._

  private val beamConfig: BeamConfig = beamScenario.beamConfig
  private val cnmConfig = beamConfig.beam.agentsim.chargingNetworkManager

  private val chargingNetworkMap: Map[Option[Id[VehicleManager]], ChargingNetwork] =
    chargingInfrastructure.chargingNetwork.map {
      case (vehicleManagerId, zones) =>
        vehicleManagerId -> new ChargingNetwork(vehicleManagerId, zones)
    }

  private val sitePowerManager = new SitePowerManager(chargingNetworkMap, beamServices)
  private val powerController = new PowerController(chargingNetworkMap, beamConfig)
  private val endOfSimulationTime: Int = DateUtils.getEndOfTime(beamConfig)

  import powerController._
  import sitePowerManager._

  import scala.concurrent.ExecutionContext.Implicits.global
  import scala.concurrent.Future
  implicit val timeout: Timeout = Timeout(10, TimeUnit.HOURS)
  implicit val debug: Debug = beamConfig.beam.debug

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

  override def loggedReceive: Receive = {
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
          // update charging vehicle with dispatched energy and schedule ChargingTimeOutScheduleTrigger
          chargingVehicle.processChargingCycle(timeBin, energyToCharge, chargingDuration).flatMap {
            case cycle if chargingIsCompleteUsing(cycle) =>
              handleEndCharging(timeBin, chargingVehicle, triggerId = triggerId)
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
              Some(ScheduleTrigger(ChargingTimeOutTrigger(timeBin + cycle.duration, vehicle), self))
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

    case TriggerWithId(ChargingTimeOutTrigger(tick, vehicle), triggerId) =>
      log.debug(s"ChargingTimeOutTrigger for vehicle ${vehicle.id} at $tick")
      vehicle.stall match {
        case Some(stall) =>
          val chargingNetwork = chargingNetworkMap(stall.vehicleManager)
          chargingNetwork.lookupVehicle(vehicle.id) match { // not taking into consideration vehicles waiting in line
            case Some(chargingVehicle) => handleEndCharging(tick, chargingVehicle, triggerId = triggerId)
            // We don't inform Actors that a private vehicle has ended charging because we don't know which agent should be informed
            case _ => log.debug(s"Vehicle ${vehicle.id} is already disconnected")
          }
        case _ => log.debug(s"Vehicle ${vehicle.id} doesn't have a stall")
      }
      sender ! CompletionNotice(triggerId)

    case ChargingPlugRequest(tick, vehicle, triggerId) =>
      log.debug(s"ChargingPlugRequest received for vehicle $vehicle at $tick and stall ${vehicle.stall}")
      vehicle.stall match {
        case Some(stall) if vehicle.isBEV | vehicle.isPHEV =>
          val chargingNetwork = chargingNetworkMap(stall.vehicleManager)
          // connecting the current vehicle
          chargingNetwork.attemptToConnectVehicle(tick, vehicle, sender) match {
            case Some(ChargingVehicle(vehicle, _, station, _, _, _, status, _)) if status.last == Waiting =>
              log.debug(
                s"Vehicle $vehicle is moved to waiting line at $tick in station $station, with {}/{} vehicles connected and {} in waiting line",
                station.connectedVehicles.size,
                station.zone.numChargers,
                station.waitingLineVehicles.size
              )
              sender ! WaitingInLine(tick, vehicle.id, triggerId)
            case Some(chargingVehicle: ChargingVehicle) =>
              handleStartCharging(tick, chargingVehicle, triggerId = triggerId)
            case _ =>
              log.debug(s"Attempt to connect vehicle ${vehicle.id} to charger failed!")
          }
        case Some(_) =>
          sender ! Failure(
            new RuntimeException(s"$vehicle is not a BEV/PHEV vehicle. Request sent by agent ${sender.path.name}")
          )
        case _ =>
          log.error(s"Vehicle ${vehicle.id} doesn't have stall, which is necessary to start charging")
      }

    case ChargingUnplugRequest(tick, vehicle, triggerId) =>
      log.debug(s"ChargingUnplugRequest received for vehicle $vehicle from plug ${vehicle.stall} at $tick")
      val physicalBounds = obtainPowerPhysicalBounds(tick, None)
      vehicle.stall match {
        case Some(stall) =>
          val chargingNetwork = chargingNetworkMap(stall.vehicleManager)
          chargingNetwork.lookupVehicle(vehicle.id) match { // not taking into consideration vehicles waiting in line
            case Some(chargingVehicle) =>
              val prevStartTime = chargingVehicle.chargingSessions.last.startTime
              val startTime = Math.min(tick, prevStartTime)
              val endTime = Math.max(tick, prevStartTime)
              val duration = endTime - startTime
              val (chargeDurationAtTick, energyToChargeAtTick) =
                dispatchEnergy(duration, chargingVehicle, physicalBounds)
              chargingVehicle.processChargingCycle(startTime, energyToChargeAtTick, chargeDurationAtTick)
              handleEndCharging(tick, chargingVehicle, Some(sender), triggerId = triggerId)
            case _ =>
              log.debug(s"Vehicle $vehicle is already disconnected at $tick")
              sender ! UnhandledVehicle(tick, vehicle.id, triggerId)
          }
        case _ =>
          log.debug(s"Cannot unplug $vehicle as it doesn't have a stall at $tick")
          sender ! UnhandledVehicle(tick, vehicle.id, triggerId)
      }

    case Finish =>
      log.info("CNM is Finishing. Now clearing the charging networks!")
      chargingNetworkMap.foreach(_._2.clearAllMappedStations())
      powerController.close()
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
            val (duration, energy) = dispatchEnergy(Int.MaxValue, chargingVehicle, physicalBounds)
            chargingVehicle.processChargingCycle(tick, energy, duration)
          }
          ScheduleTrigger(ChargingTimeOutTrigger(nextTimeBin(tick) - 1, vehicle), self)
      }
      .toVector
  }

  /**
    * Connect the vehicle
    * @param tick current time
    * @param chargingVehicle charging vehicle information
    */
  private def handleStartCharging(tick: Int, chargingVehicle: ChargingVehicle, triggerId: Long): Unit = {
    val ChargingVehicle(vehicle, stall, _, _, _, theSender, _, _) = chargingVehicle
    log.debug(s"Starting charging for vehicle $vehicle at $tick")
    val physicalBounds = obtainPowerPhysicalBounds(tick, None)
    vehicle.connectToChargingPoint(tick)
    theSender ! StartingRefuelSession(tick, vehicle.id, triggerId)
    handleStartChargingHelper(tick, chargingVehicle)
    val (chargeDurationAtTick, energyToChargeAtTick) =
      dispatchEnergy(nextTimeBin(tick) - tick, chargingVehicle, physicalBounds)
    chargingVehicle.processChargingCycle(tick, energyToChargeAtTick, chargeDurationAtTick)
    val endTime = chargingVehicle.computeSessionEndTime
    if (endTime < nextTimeBin(tick))
      scheduler ! ScheduleTrigger(ChargingTimeOutTrigger(endTime, vehicle), self)
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
    currentSenderMaybe: Option[ActorRef] = None,
    triggerId: Long
  ): Unit = {
    val ChargingVehicle(vehicle, stall, _, _, _, _, _, _) = chargingVehicle
    val chargingNetwork = chargingNetworkMap(stall.vehicleManager)
    chargingNetwork.disconnectVehicle(chargingVehicle) match {
      case Some(cv) =>
        log.debug(s"Vehicle ${chargingVehicle.vehicle} has been disconnected from the charging station")
        handleRefueling(chargingVehicle)
        handleEndChargingHelper(tick, chargingVehicle)
        vehicle.disconnectFromChargingPoint()
        parkingManager ! ReleaseParkingStall(vehicle.stall.get, triggerId)
        vehicle.unsetParkingStall()
        currentSenderMaybe.foreach(_ ! EndingRefuelSession(tick, vehicle.id, triggerId))
        chargingNetwork.processWaitingLine(tick, cv.chargingStation).foreach(handleStartCharging(tick, _, triggerId))
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
    chargingVehicle.latestChargingCycle.foreach { cycle =>
      val vehicle = chargingVehicle.vehicle
      log.debug(s"Charging vehicle $vehicle. Stall ${vehicle.stall}. Provided energy of = ${cycle.energy} J")
      vehicle.addFuel(cycle.energy)
      collectObservedLoadInKW(chargingVehicle, cycle)
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

object ChargingNetworkManager {
  object DebugReport
  case class ChargingZonesInquiry()
  case class PlanEnergyDispatchTrigger(tick: Int) extends Trigger
  case class ChargingTimeOutTrigger(tick: Int, vehicle: BeamVehicle) extends Trigger
  case class ChargingPlugRequest(tick: Int, vehicle: BeamVehicle, triggerId: Long) extends HasTriggerId
  case class ChargingUnplugRequest(tick: Int, vehicle: BeamVehicle, triggerId: Long) extends HasTriggerId
  case class StartingRefuelSession(tick: Int, vehicleId: Id[BeamVehicle], triggerId: Long) extends HasTriggerId
  case class EndingRefuelSession(tick: Int, vehicleId: Id[BeamVehicle], triggerId: Long) extends HasTriggerId
  case class WaitingInLine(tick: Int, vehicleId: Id[BeamVehicle], triggerId: Long) extends HasTriggerId
  case class UnhandledVehicle(tick: Int, vehicleId: Id[BeamVehicle], triggerId: Long) extends HasTriggerId

  final case class ChargingZone(
    geoId: Id[_],
    tazId: Id[TAZ],
    parkingType: ParkingType,
    numChargers: Int,
    chargingPointType: ChargingPointType,
    pricingModel: PricingModel,
    vehicleManager: Option[Id[VehicleManager]]
  ) {
    val id: String = constructChargingZoneKey(vehicleManager, geoId, parkingType, chargingPointType)
    override def equals(that: Any): Boolean =
      that match {
        case that: ChargingZone =>
          that.hashCode() == hashCode
        case _ => false
      }
    override def hashCode: Int = id.hashCode()
  }

  def constructChargingZoneKey(
    vehicleManager: Option[Id[VehicleManager]],
    geoId: Id[_],
    parkingType: ParkingType,
    chargingPointType: ChargingPointType
  ): String = {
    s"cs_${vehicleManager.map(_.toString).getOrElse("")}_${geoId}_${parkingType}_$chargingPointType"
  }

  def props(
    beamServices: BeamServices,
    chargingInfrastructure: ParkingAndChargingInfrastructure,
    parkingManager: ActorRef,
    scheduler: ActorRef
  ): Props = {
    Props(new ChargingNetworkManager(beamServices, chargingInfrastructure, parkingManager, scheduler))
  }

}
