package beam.agentsim.infrastructure

import akka.actor.Status.Failure
import akka.actor.{ActorLogging, ActorRef, Cancellable, Props}
import akka.pattern.pipe
import akka.util.Timeout
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.agents.vehicles.VehicleManager.ReservedFor
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.RefuelSessionEvent.{NotApplicable, ShiftStatus}
import beam.agentsim.infrastructure.ChargingNetwork.{ChargingStation, ChargingStatus, ChargingVehicle}
import beam.agentsim.infrastructure.ParkingInquiry.ParkingSearchMode.EnRoute
import beam.agentsim.infrastructure.power.SitePowerManager
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.agentsim.scheduler.{HasTriggerId, Trigger}
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import beam.sim.config.BeamConfig.Beam.Debug
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
    with ActorLogging
    with ChargingNetworkManagerHelper
    with ScaleUpCharging {
  import ChargingNetworkManager._
  import ChargingStatus._

  protected val beamConfig: BeamConfig = beamServices.beamScenario.beamConfig
  import scala.concurrent.ExecutionContext.Implicits.global
  import scala.concurrent.Future
  implicit val timeout: Timeout = Timeout(10, TimeUnit.HOURS)
  implicit val debug: Debug = beamConfig.beam.debug
  private var timeSpentToPlanEnergyDispatchTrigger: Long = 0
  private var nHandledPlanEnergyDispatchTrigger: Int = 0

  private val maybeDebugReport: Option[Cancellable] = if (beamServices.beamConfig.beam.debug.debugEnabled) {
    Some(context.system.scheduler.scheduleWithFixedDelay(10.seconds, 30.seconds, self, DebugReport)(context.dispatcher))
  } else {
    None
  }

  protected val chargingNetworkHelper: ChargingNetworkHelper = ChargingNetworkHelper(chargingNetwork, rideHailNetwork)

  protected val sitePowerManager =
    new SitePowerManager(chargingNetworkHelper, beamServices)

  override def postStop(): Unit = {
    maybeDebugReport.foreach(_.cancel())
    log.debug(
      s"timeSpentToPlanEnergyDispatchTrigger: $timeSpentToPlanEnergyDispatchTrigger ms, " +
      s"nHandledPlanEnergyDispatchTrigger: $nHandledPlanEnergyDispatchTrigger, " +
      s"AVG: ${timeSpentToPlanEnergyDispatchTrigger.toDouble / nHandledPlanEnergyDispatchTrigger}"
    )
    super.postStop()
  }

  override def loggedReceive: Receive = super[ScaleUpCharging].loggedReceive orElse {
    case DebugReport =>
      log.debug(
        s"timeSpentToPlanEnergyDispatchTrigger: $timeSpentToPlanEnergyDispatchTrigger ms, " +
        s"nHandledPlanEnergyDispatchTrigger: $nHandledPlanEnergyDispatchTrigger, " +
        s"AVG: ${timeSpentToPlanEnergyDispatchTrigger.toDouble / nHandledPlanEnergyDispatchTrigger}"
      )

    case inquiry: ParkingInquiry =>
      log.debug(s"Received parking inquiry: $inquiry")
      chargingNetworkHelper.get(inquiry.reservedFor.managerId).processParkingInquiry(inquiry) match {
        case Some(parkingResponse) =>
          inquiry.beamVehicle foreach (v => vehicle2InquiryMap.put(v.id, inquiry))
          sender() ! parkingResponse
        case _ => (parkingNetworkManager ? inquiry).pipeTo(sender())
      }

    case TriggerWithId(InitializeTrigger(_), triggerId) =>
      log.info("ChargingNetworkManager is Starting!")
      Future(scheduler ? ScheduleTrigger(PlanEnergyDispatchTrigger(0), self))
        .map(_ => CompletionNotice(triggerId, Vector()))
        .pipeTo(sender())

    case TriggerWithId(PlanEnergyDispatchTrigger(timeBin), triggerId) =>
      val s = System.currentTimeMillis
      log.debug(s"Planning energy dispatch for vehicles currently connected to a charging point, at t=$timeBin")
      sitePowerManager.updateChargingProfiles(timeBin)
      log.debug("Total Load estimated is {} at tick {}", loadEstimate.values.sum, timeBin)
      val simulatedParkingInquiries = simulateEventsIfScalingEnabled(timeBin, triggerId)
      log.debug("number of simulatedParkingInquiries is {} at tick {}", simulatedParkingInquiries.size, timeBin)
      // obtaining physical bounds
      val triggers = chargingNetworkHelper.lookUpPluggedInVehiclesAt(timeBin).par.flatMap { case (_, chargingVehicle) =>
        // Refuel
        handleRefueling(chargingVehicle)
        // Calculate the energy to charge and prepare for next current cycle of charging
        dispatchEnergyAndProcessChargingCycle(
          chargingVehicle,
          timeBin,
          timeBin + beamConfig.beam.agentsim.chargingNetworkManager.timeStepInSeconds,
          triggerId
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
      sender ! CompletionNotice(
        triggerId,
        triggers.toIndexedSeq ++ nextStepPlanningTriggers ++ simulatedParkingInquiries
      )

    case TriggerWithId(ChargingTimeOutTrigger(tick, vehicle), triggerId) =>
      log.debug(s"ChargingTimeOutTrigger for vehicle ${vehicle.id} at $tick")
      val vehicleEndedCharging = vehicle.stall match {
        case Some(stall) =>
          chargingNetworkHelper.get(stall.reservedFor.managerId).endChargingSession(vehicle.id, tick) map {
            handleEndCharging(tick, _, triggerId)
          } getOrElse {
            log.debug(s"Vehicle ${vehicle.id} has already ended charging")
            None
          }
        case _ =>
          log.debug(s"Vehicle ${vehicle.id} doesn't have a stall")
          None
      }
      vehicleEndedCharging match {
        case Some(ChargingVehicle(_, _, _, _, _, _, _, _, theSender, _, _)) =>
          theSender ! EndingRefuelSession(tick, vehicle.id, triggerId)
        case _ =>
          sender ! CompletionNotice(triggerId)
      }

    case request @ ChargingPlugRequest(tick, vehicle, stall, _, triggerId, _, _) =>
      log.debug(s"ChargingPlugRequest received for vehicle $vehicle at $tick and stall ${vehicle.stall}")
      val responseHasTriggerId = if (vehicle.isEV) {
        // connecting the current vehicle
        val activityType = vehicle2InquiryMap
          .get(vehicle.id)
          .map {
            case ParkingInquiry(_, _, _, _, _, _, _, _, _, _, `EnRoute`, _, _)    => EnRoute.toString
            case ParkingInquiry(_, activityType, _, _, _, _, _, _, _, _, _, _, _) => activityType
          }
          .getOrElse("")
        chargingNetworkHelper
          .get(stall.reservedFor.managerId)
          .processChargingPlugRequest(request, activityType, sender()) map {
          case chargingVehicle if chargingVehicle.chargingStatus.last.status == WaitingAtStation =>
            log.debug(
              s"Vehicle $vehicle is moved to waiting line at $tick in station ${chargingVehicle.chargingStation}, " +
              s"with {} vehicles connected and {} in grace period and {} in waiting line",
              chargingVehicle.chargingStation.howManyVehiclesAreCharging,
              chargingVehicle.chargingStation.howManyVehiclesAreInGracePeriodAfterCharging,
              chargingVehicle.chargingStation.howManyVehiclesAreWaiting
            )
            WaitingToCharge(tick, vehicle.id, triggerId)
          case chargingVehicle =>
            vehicle2InquiryMap.remove(vehicle.id)
            handleStartCharging(tick, chargingVehicle, triggerId = triggerId)
            collectVehicleRequestInfo(chargingVehicle)
            StartingRefuelSession(tick, triggerId)
        } getOrElse Failure(
          new RuntimeException(
            s"Cannot find a ${request.stall.reservedFor} station identified with tazId ${request.stall.tazId}, " +
            s"parkingType ${request.stall.parkingType} and chargingPointType ${request.stall.chargingPointType.get}!"
          )
        )
      } else {
        Failure(new RuntimeException(s"$vehicle is not a BEV/PHEV vehicle. Request sent by agent ${sender.path.name}"))
      }
      sender ! responseHasTriggerId

    case ChargingUnplugRequest(tick, vehicle, triggerId) =>
      log.debug(s"ChargingUnplugRequest received for vehicle $vehicle from plug ${vehicle.stall} at $tick")
      val responseHasTriggerId = vehicle.stall match {
        case Some(stall) =>
          chargingNetworkHelper.get(stall.reservedFor.managerId).disconnectVehicle(vehicle.id, tick) match {
            case Some(chargingVehicle @ ChargingVehicle(_, _, station, _, _, _, _, _, _, listStatus, sessions)) =>
              if (sessions.nonEmpty && !listStatus.exists(_.status == GracePeriod)) {
                // If the vehicle was still charging
                val unplugTime = currentTimeBin(tick)
                val index = sessions.indexWhere(x => currentTimeBin(x.startTime) == unplugTime && x.startTime <= tick)
                val (startTime, endTime) = if (index == -1) (unplugTime, tick) else (sessions(index).startTime, tick)
                dispatchEnergyAndProcessChargingCycle(chargingVehicle, startTime, endTime, triggerId, true)
                handleEndCharging(endTime, chargingVehicle, triggerId)
              }
              chargingNetwork
                .processWaitingLine(tick, station)
                .foreach { newChargingVehicle =>
                  self ! ChargingPlugRequest(
                    tick,
                    newChargingVehicle.vehicle,
                    newChargingVehicle.stall,
                    newChargingVehicle.personId,
                    triggerId,
                    newChargingVehicle.shiftStatus,
                    newChargingVehicle.shiftDuration
                  )
                }
              val (_, totEnergy) = chargingVehicle.calculateChargingSessionLengthAndEnergyInJoule
              UnpluggingVehicle(tick, totEnergy, triggerId)
            case _ =>
              log.debug(s"Vehicle $vehicle is already disconnected or unhandled at $tick")
              UnhandledVehicle(tick, vehicle.id, triggerId)
          }
        case _ =>
          log.debug(s"Cannot unplug $vehicle as it doesn't have a stall at $tick")
          UnhandledVehicle(tick, vehicle.id, triggerId)
      }
      sender ! responseHasTriggerId

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
      sitePowerManager.close()
      context.children.foreach(_ ! Finish)
      context.stop(self)
  }

  protected def getScheduler: ActorRef = scheduler
  protected def getBeamServices: BeamServices = beamServices
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

  case class ChargingNetworkHelper(chargingNetwork: ChargingNetwork[_], rideHailNetwork: ChargingNetwork[_]) {

    lazy val allChargingStations: List[ChargingStation] =
      chargingNetwork.chargingStations ++ rideHailNetwork.chargingStations

    private var allConnectedVehicles = chargingNetwork.pluggedInVehicles ++ rideHailNetwork.pluggedInVehicles
    private var currentTime: Double = -1

    def lookUpPluggedInVehiclesAt(time: Double): Map[Id[BeamVehicle], ChargingVehicle] = {
      if (currentTime < time) {
        allConnectedVehicles = chargingNetwork.pluggedInVehicles ++ rideHailNetwork.pluggedInVehicles
        currentTime = time
      }
      allConnectedVehicles
    }

    /**
      * @param managerId vehicle manager id
      * @return
      */
    def get(managerId: Id[VehicleManager]): ChargingNetwork[_] = {
      get(VehicleManager.getReservedFor(managerId).get)
    }

    /**
      * @param managerType vehicle manager type
      * @return
      */
    def get(managerType: ReservedFor): ChargingNetwork[_] = {
      managerType match {
        case VehicleManager.TypeEnum.RideHail => rideHailNetwork
        case _                                => chargingNetwork
      }
    }
  }
}
