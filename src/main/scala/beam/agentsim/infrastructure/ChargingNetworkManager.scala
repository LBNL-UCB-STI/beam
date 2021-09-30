package beam.agentsim.infrastructure

import akka.actor.Status.Failure
import akka.actor.{ActorLogging, ActorRef, Cancellable, Props}
import akka.pattern.pipe
import akka.util.Timeout
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.agents.vehicles.{BeamVehicle, VehicleManager}
import beam.agentsim.events.RefuelSessionEvent.{NotApplicable, ShiftStatus}
import beam.agentsim.events.{ChargingPlugInEvent, ChargingPlugOutEvent, RefuelSessionEvent}
import beam.agentsim.infrastructure.ChargingNetwork.{ChargingCycle, ChargingStation, ChargingStatus, ChargingVehicle}
import beam.agentsim.infrastructure.power.SitePowerManager.PhysicalBounds
import beam.agentsim.infrastructure.power.{PowerController, SitePowerManager}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.agentsim.scheduler.{HasTriggerId, Trigger}
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import beam.sim.config.BeamConfig.Beam.Debug
import beam.utils.DateUtils
import beam.utils.logging.LoggingMessageActor
import beam.utils.logging.pattern.ask
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person

import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * Created by haitamlaarabi
  */

class ChargingNetworkManager(
  beamServices: BeamServices,
  chargingNetwork: ChargingNetwork[_],
  rideHailNetwork: ChargingNetwork[_],
  parkingNetworkManager: ActorRef,
  scheduler: ActorRef
) extends LoggingMessageActor
    with ActorLogging {
  import ChargingNetworkManager._
  import ChargingStatus._
  import beamServices._

  private val beamConfig: BeamConfig = beamScenario.beamConfig
  private val cnmConfig = beamConfig.beam.agentsim.chargingNetworkManager

  private val sitePowerManager = new SitePowerManager(chargingNetwork, rideHailNetwork, beamServices)

  private val powerController =
    new PowerController(chargingNetwork, rideHailNetwork, beamConfig, sitePowerManager.unlimitedPhysicalBounds)
  private val endOfSimulationTime: Int = DateUtils.getEndOfTime(beamConfig)

  import scala.concurrent.ExecutionContext.Implicits.global
  import scala.concurrent.Future
  implicit val timeout: Timeout = Timeout(10, TimeUnit.HOURS)
  implicit val debug: Debug = beamConfig.beam.debug

  private val parallelismWindow: Int = beamConfig.beam.agentsim.schedulerParallelismWindow

  private def currentTimeBin(tick: Int): Int = cnmConfig.timeStepInSeconds * (tick / cnmConfig.timeStepInSeconds)
  private def nextTimeBin(tick: Int): Int = currentTimeBin(tick) + cnmConfig.timeStepInSeconds

  private var timeSpentToPlanEnergyDispatchTrigger: Long = 0
  private var nHandledPlanEnergyDispatchTrigger: Int = 0

  private val maybeDebugReport: Option[Cancellable] = if (beamServices.beamConfig.beam.debug.debugEnabled) {
    Some(context.system.scheduler.scheduleWithFixedDelay(10.seconds, 30.seconds, self, DebugReport)(context.dispatcher))
  } else {
    None
  }

  override def postStop(): Unit = {
    maybeDebugReport.foreach(_.cancel())
    log.debug(
      s"timeSpentToPlanEnergyDispatchTrigger: $timeSpentToPlanEnergyDispatchTrigger ms, " +
      s"nHandledPlanEnergyDispatchTrigger: $nHandledPlanEnergyDispatchTrigger, " +
      s"AVG: ${timeSpentToPlanEnergyDispatchTrigger.toDouble / nHandledPlanEnergyDispatchTrigger}"
    )
    super.postStop()
  }

  override def loggedReceive: Receive = {
    case DebugReport =>
      log.debug(
        s"timeSpentToPlanEnergyDispatchTrigger: $timeSpentToPlanEnergyDispatchTrigger ms, " +
        s"nHandledPlanEnergyDispatchTrigger: $nHandledPlanEnergyDispatchTrigger, " +
        s"AVG: ${timeSpentToPlanEnergyDispatchTrigger.toDouble / nHandledPlanEnergyDispatchTrigger}"
      )

    case inquiry: ParkingInquiry =>
      log.debug(s"Received parking inquiry: $inquiry")
      getAppropriateChargingNetwork(inquiry.reservedFor.managerId).processParkingInquiry(inquiry) match {
        case Some(parkingResponse) => sender() ! parkingResponse
        case _                     => (parkingNetworkManager ? inquiry).pipeTo(sender())
      }

    case TriggerWithId(InitializeTrigger(_), triggerId) =>
      log.info("ChargingNetworkManager is Starting!")
      Future(scheduler ? ScheduleTrigger(PlanEnergyDispatchTrigger(0), self))
        .map(_ => CompletionNotice(triggerId, Vector()))
        .pipeTo(sender())

    case TriggerWithId(PlanEnergyDispatchTrigger(timeBin), triggerId) =>
      val s = System.currentTimeMillis
      log.debug(s"Planning energy dispatch for vehicles currently connected to a charging point, at t=$timeBin")
      val estimatedLoad = sitePowerManager.requiredPowerInKWOverNextPlanningHorizon(timeBin)
      log.debug("Total Load estimated is {} at tick {}", estimatedLoad.values.sum, timeBin)

      // obtaining physical bounds
      val physicalBounds = powerController.obtainPowerPhysicalBounds(timeBin, Some(estimatedLoad))

      val triggers = chargingNetwork.connectedVehicles.par.flatMap { case (_, chargingVehicle) =>
        // Refuel
        handleRefueling(chargingVehicle)
        // Calculate the energy to charge and prepare for next current cycle of charging
        dispatchEnergyAndProcessChargingCycle(
          chargingVehicle,
          timeBin,
          timeBin + cnmConfig.timeStepInSeconds,
          physicalBounds,
          triggerId,
          false
        )
      }

      val nextStepPlanningTriggers =
        if (!isEndOfSimulation(timeBin))
          Vector(ScheduleTrigger(PlanEnergyDispatchTrigger(nextTimeBin(timeBin)), self))
        else
          Vector()

      val e = System.currentTimeMillis()
      nHandledPlanEnergyDispatchTrigger += 1
      timeSpentToPlanEnergyDispatchTrigger += e - s
      log.debug(s"timeSpentToPlanEnergyDispatchTrigger: $timeSpentToPlanEnergyDispatchTrigger. tick: $timeBin")

      sender ! CompletionNotice(triggerId, triggers.toIndexedSeq ++ nextStepPlanningTriggers)

    case TriggerWithId(ChargingTimeOutTrigger(tick, vehicle), triggerId) =>
      log.debug(s"ChargingTimeOutTrigger for vehicle ${vehicle.id} at $tick")
      vehicle.stall match {
        case Some(stall) =>
          getAppropriateChargingNetwork(stall.reservedFor.managerId).endChargingSession(vehicle.id, tick) map {
            handleEndCharging(tick, _, triggerId, false)
          } getOrElse log.debug(s"Vehicle ${vehicle.id} has already ended charging")
        case _ => log.debug(s"Vehicle ${vehicle.id} doesn't have a stall")
      }
      sender ! CompletionNotice(triggerId)

    case request @ ChargingPlugRequest(tick, vehicle, stall, _, triggerId, _, _) =>
      log.debug(s"ChargingPlugRequest received for vehicle $vehicle at $tick and stall ${vehicle.stall}")
      if (vehicle.isBEV || vehicle.isPHEV) {
        // connecting the current vehicle
        getAppropriateChargingNetwork(stall.reservedFor.managerId).processChargingPlugRequest(request, sender()) map {
          case chargingVehicle if chargingVehicle.chargingStatus.last.status == WaitingAtStation =>
            log.debug(
              s"Vehicle $vehicle is moved to waiting line at $tick in station ${chargingVehicle.chargingStation}, " +
              s"with {} vehicles connected and {} in grace period and {} in waiting line",
              chargingVehicle.chargingStation.howManyVehiclesAreCharging,
              chargingVehicle.chargingStation.howManyVehiclesAreInGracePeriodAfterCharging,
              chargingVehicle.chargingStation.howManyVehiclesAreWaiting
            )
            sender() ! WaitingToCharge(tick, vehicle.id, triggerId)
          case chargingVehicle =>
            handleStartCharging(tick, chargingVehicle, triggerId = triggerId)
        }
      } else {
        sender() ! Failure(
          new RuntimeException(s"$vehicle is not a BEV/PHEV vehicle. Request sent by agent ${sender.path.name}")
        )
      }

    case ChargingUnplugRequest(tick, vehicle, triggerId) =>
      log.debug(s"ChargingUnplugRequest received for vehicle $vehicle from plug ${vehicle.stall} at $tick")
      val bounds = powerController.obtainPowerPhysicalBounds(tick, None)
      vehicle.stall match {
        case Some(stall) =>
          getAppropriateChargingNetwork(stall.reservedFor.managerId).disconnectVehicle(vehicle.id, tick) match {
            case Some(chargingVehicle @ ChargingVehicle(_, _, station, _, _, _, _, _, status, sessions)) =>
              if (sessions.nonEmpty && !status.exists(_.status == GracePeriod)) {
                // If the vehicle was still charging
                val unplugTime = currentTimeBin(tick)
                val index = sessions.indexWhere(x => currentTimeBin(x.startTime) == unplugTime && x.startTime <= tick)
                val (startTime, endTime) = if (index == -1) (unplugTime, tick) else (sessions(index).startTime, tick)
                dispatchEnergyAndProcessChargingCycle(chargingVehicle, startTime, endTime, bounds, triggerId, true)
              }
              val (_, totEnergy) = chargingVehicle.calculateChargingSessionLengthAndEnergyInJoule
              sender ! UnpluggingVehicle(tick + parallelismWindow, totEnergy, triggerId)
              chargingNetwork
                .processWaitingLine(tick, station)
                .foreach { newChargingVehicle =>
                  self ! ChargingPlugRequest(
                    tick + parallelismWindow,
                    newChargingVehicle.vehicle,
                    newChargingVehicle.stall,
                    newChargingVehicle.personId,
                    triggerId,
                    newChargingVehicle.shiftStatus,
                    newChargingVehicle.shiftDuration
                  )
                }
            case _ =>
              log.debug(s"Vehicle $vehicle is already disconnected or unhandled at $tick")
              sender ! UnhandledVehicle(tick + parallelismWindow, vehicle.id, triggerId)
          }
        case _ =>
          log.debug(s"Cannot unplug $vehicle as it doesn't have a stall at $tick")
          sender ! UnhandledVehicle(tick + parallelismWindow, vehicle.id, triggerId)
      }

    case Finish =>
      log.info("CNM is Finishing. Now clearing the charging networks!")
      val nbWaitingVehicles = chargingNetwork.waitingLineVehicles.size + rideHailNetwork.waitingLineVehicles.size
      if (nbWaitingVehicles > 0) {
        log.warning(
          s"There were $nbWaitingVehicles vehicles waiting to be charged." +
          s"It might be due to lack of charging infrastructure or something is broken"
        )
      }
      chargingNetwork.clearAllMappedStations()
      rideHailNetwork.clearAllMappedStations()
      powerController.close()
      context.children.foreach(_ ! Finish)
      context.stop(self)
  }

  /**
    * @param reservedFor vehicle manager id
    * @return
    */
  private def getAppropriateChargingNetwork(reservedFor: Id[VehicleManager]): ChargingNetwork[_] = {
    VehicleManager.getReservedFor(reservedFor) match {
      case VehicleManager.TypeEnum.RideHail => rideHailNetwork
      case _                                => chargingNetwork
    }
  }

  /**
    * if this is the last timebin of the simulation
    * @param tick current tick
    * @return a boolean
    */
  private def isEndOfSimulation(tick: Int) = nextTimeBin(tick) >= endOfSimulationTime

  /**
    * @param chargingVehicle the vehicle being charged
    * @param startTime the start time
    * @param endTime the end time
    * @param physicalBounds physical bounds
    * @param triggerId trigger Id
    * @param interruptCharging True if the charging should be interrupted
    * @return
    */
  private def dispatchEnergyAndProcessChargingCycle(
    chargingVehicle: ChargingVehicle,
    startTime: Int,
    endTime: Int,
    physicalBounds: Map[ChargingStation, PhysicalBounds],
    triggerId: Long,
    interruptCharging: Boolean
  ): Option[ScheduleTrigger] = {
    assume(endTime - startTime >= 0, s"timeInterval should not be negative! startTime $startTime endTime $endTime")
    // Calculate the energy to charge each vehicle connected to the a charging station
    val updatedEndTime = chargingVehicle.chargingShouldEndAt.map(_ - parallelismWindow).getOrElse(endTime)
    val duration = Math.max(0, updatedEndTime - startTime)
    val maxCycleDuration = Math.min(
      nextTimeBin(startTime) - startTime,
      chargingVehicle.chargingShouldEndAt.map(_ - parallelismWindow - startTime).getOrElse(Int.MaxValue)
    )
    chargingVehicle.checkAndCorrectCycleAfterInterruption(updatedEndTime)
    val (chargingDuration, energyToCharge) = sitePowerManager.dispatchEnergy(duration, chargingVehicle, physicalBounds)
    log.debug(
      s"dispatchEnergyAndProcessChargingCycle. startTime:$startTime, endTime:$endTime, updatedEndTime:$updatedEndTime, " +
      s"duration:$duration, maxCycleDuration:$maxCycleDuration, chargingVehicle:$chargingVehicle, " +
      s"chargingDuration:$chargingDuration"
    )
    // update charging vehicle with dispatched energy and schedule ChargingTimeOutScheduleTrigger
    chargingVehicle
      .processCycle(startTime, startTime + chargingDuration, energyToCharge, maxCycleDuration)
      .flatMap {
        case cycle if chargingIsCompleteUsing(cycle) || interruptCharging =>
          handleEndCharging(cycle.endTime, chargingVehicle, triggerId, interruptCharging)
          None
        case cycle if chargingNotCompleteUsing(cycle) && !isEndOfSimulation(startTime) =>
          log.debug(
            s"Vehicle {} is still charging @ Stall: {}. Provided energy: {} J. Remaining: {} J",
            chargingVehicle.vehicle.id,
            chargingVehicle.stall,
            cycle.energyToCharge,
            energyToCharge
          )
          None
        case cycle =>
          // charging is going to end during this current session
          Some(ScheduleTrigger(ChargingTimeOutTrigger(cycle.endTime, chargingVehicle.vehicle), self))
      }
  }

  /**
    * Connect the vehicle
    * @param tick current time
    * @param chargingVehicle charging vehicle information
    */
  private def handleStartCharging(
    tick: Int,
    chargingVehicle: ChargingVehicle,
    triggerId: Long
  ): Unit = {
    val nextTick = nextTimeBin(tick)
    val vehicle = chargingVehicle.vehicle
    if (vehicle.stall.isEmpty)
      vehicle.useParkingStall(chargingVehicle.stall)
    log.debug(s"Starting charging for vehicle $vehicle at $tick")
    val physicalBounds = powerController.obtainPowerPhysicalBounds(tick, None)
    chargingVehicle.theSender ! StartingRefuelSession(tick + parallelismWindow, triggerId)
    handleStartChargingHelper(tick, chargingVehicle, beamServices)
    dispatchEnergyAndProcessChargingCycle(chargingVehicle, tick, nextTick, physicalBounds, triggerId, false).foreach(
      scheduler ! _
    )
  }

  /**
    * Disconnect the vehicle
    * @param tick current time
    * @param chargingVehicle charging vehicle information
    * @param triggerId the trigger
    * @param chargingInterrupted Boolean
    */
  private def handleEndCharging(
    tick: Int,
    chargingVehicle: ChargingVehicle,
    triggerId: Long,
    chargingInterrupted: Boolean
  ): Unit = {
    val result = chargingVehicle.chargingStatus.last.status match {
      case Connected =>
        chargingVehicle.chargingStation.endCharging(chargingVehicle.vehicle.id, tick) orElse {
          log.debug(
            s"Vehicle ${chargingVehicle.vehicle.id} has already ended charging"
          )
          None
        }
      case _ => Some(chargingVehicle)
    }
    if (result.isDefined) {
      handleRefueling(chargingVehicle)
      handleEndChargingHelper(tick, chargingVehicle, beamServices)
      if (!chargingInterrupted)
        chargingVehicle.theSender ! EndingRefuelSession(tick + parallelismWindow, chargingVehicle.vehicle.id, triggerId)
    }
  }

  /**
    * Refuel the vehicle using last charging session and collect the corresponding load
    * @param chargingVehicle vehicle charging information
    */
  private def handleRefueling(chargingVehicle: ChargingVehicle): Unit = {
    chargingVehicle.refuel.foreach { case ChargingCycle(startTime, endTime, _, _) =>
      sitePowerManager.collectObservedLoadInKW(
        startTime,
        endTime - startTime,
        chargingVehicle.vehicle,
        chargingVehicle.chargingStation
      )
    }
  }

  /**
    * if charging completed then duration of charging should be zero
    * @param cycle the latest charging cycle
    * @return
    */
  private def chargingIsCompleteUsing(cycle: ChargingCycle) = (cycle.endTime - cycle.startTime) == 0

  /**
    * if charging won't complete during the current cycle
    * @param cycle the latest charging cycle
    * @return
    */
  private def chargingNotCompleteUsing(cycle: ChargingCycle) = (cycle.endTime - cycle.startTime) >= cycle.maxDuration
}

object ChargingNetworkManager extends LazyLogging {
  object DebugReport
  case class PlanEnergyDispatchTrigger(tick: Int) extends Trigger
  case class ChargingTimeOutTrigger(tick: Int, vehicle: BeamVehicle) extends Trigger

  case class ChargingPlugRequest(
    tick: Int,
    vehicle: BeamVehicle,
    stall: ParkingStall,
    personId: Id[Person],
    triggerId: Long,
    shiftStatus: ShiftStatus = NotApplicable,
    shiftDuration: Option[Int] = None
  ) extends HasTriggerId
  case class ChargingUnplugRequest(tick: Int, vehicle: BeamVehicle, triggerId: Long) extends HasTriggerId
  case class StartingRefuelSession(tick: Int, triggerId: Long) extends HasTriggerId
  case class EndingRefuelSession(tick: Int, vehicleId: Id[BeamVehicle], triggerId: Long) extends HasTriggerId
  case class WaitingToCharge(tick: Int, vehicleId: Id[BeamVehicle], triggerId: Long) extends HasTriggerId
  case class UnhandledVehicle(tick: Int, vehicleId: Id[BeamVehicle], triggerId: Long) extends HasTriggerId
  case class UnpluggingVehicle(tick: Int, energyCharged: Double, triggerId: Long) extends HasTriggerId

  def props(
    beamServices: BeamServices,
    chargingNetwork: ChargingNetwork[_],
    rideHailNetwork: ChargingNetwork[_],
    parkingManager: ActorRef,
    scheduler: ActorRef
  ): Props = {
    Props(new ChargingNetworkManager(beamServices, chargingNetwork, rideHailNetwork, parkingManager, scheduler))
  }

  /**
    * process the event ChargingPlugInEvent
    * @param currentTick current time
    * @param chargingVehicle vehicle charging information
    */
  private def handleStartChargingHelper(
    currentTick: Int,
    chargingVehicle: ChargingVehicle,
    beamServices: BeamServices
  ): Unit = {
    import chargingVehicle._
    val chargingPlugInEvent = new ChargingPlugInEvent(
      tick = currentTick,
      stall = stall,
      locationWGS = beamServices.geo.utm2Wgs(stall.locationUTM),
      vehId = vehicle.id,
      primaryFuelLevel = vehicle.primaryFuelLevelInJoules,
      secondaryFuelLevel = Some(vehicle.secondaryFuelLevelInJoules)
    )
    logger.debug(s"ChargingPlugInEvent: $chargingPlugInEvent")
    beamServices.matsimServices.getEvents.processEvent(chargingPlugInEvent)
  }

  /**
    * Calculates the duration of the refuel session, the provided energy and throws corresponding events
    * @param currentTick current time
    * @param chargingVehicle vehicle charging information
    */
  def handleEndChargingHelper(currentTick: Int, chargingVehicle: ChargingVehicle, beamServices: BeamServices): Unit = {
    val (totDuration, totEnergy) = chargingVehicle.calculateChargingSessionLengthAndEnergyInJoule
    val vehicle = chargingVehicle.vehicle
    val stall = chargingVehicle.stall
    logger.debug(
      s"Vehicle ${chargingVehicle.vehicle} was disconnected at time {} with {} J delivered during {} sec",
      currentTick,
      totEnergy,
      totDuration
    )
    // Refuel Session
    val refuelSessionEvent = new RefuelSessionEvent(
      currentTick,
      stall.copy(locationUTM = beamServices.geo.utm2Wgs(stall.locationUTM)),
      totEnergy,
      vehicle.primaryFuelLevelInJoules - totEnergy,
      totDuration,
      vehicle.id,
      vehicle.beamVehicleType,
      chargingVehicle.personId,
      chargingVehicle.shiftStatus
    )
    logger.debug(s"RefuelSessionEvent: $refuelSessionEvent")
    beamServices.matsimServices.getEvents.processEvent(refuelSessionEvent)
    val chargingPlugOutEvent: ChargingPlugOutEvent = new ChargingPlugOutEvent(
      currentTick,
      stall.copy(locationUTM = beamServices.geo.utm2Wgs(stall.locationUTM)),
      vehicle.id,
      vehicle.primaryFuelLevelInJoules,
      Some(vehicle.secondaryFuelLevelInJoules)
    )
    logger.debug(s"ChargingPlugOutEvent: $chargingPlugOutEvent")
    beamServices.matsimServices.getEvents.processEvent(chargingPlugOutEvent)
  }
}
