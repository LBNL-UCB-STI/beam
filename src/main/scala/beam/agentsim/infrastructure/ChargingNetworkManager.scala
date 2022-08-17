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
import beam.agentsim.infrastructure.ParkingInquiry.ParkingActivityType
import beam.agentsim.infrastructure.ParkingInquiry.ParkingSearchMode.EnRouteCharging
import beam.agentsim.infrastructure.power.{PowerController, SitePowerManager}
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
import org.matsim.api.core.v01.population.{Activity, Person}

import java.util.concurrent.TimeUnit
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * Created by haitamlaarabi
  */

class ChargingNetworkManager(
  beamServices: BeamServices,
  chargingNetwork: ChargingNetwork,
  rideHailNetwork: ChargingNetwork,
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

  protected val chargingEndTimeInSeconds: Map[Id[Person], Int] =
    beamServices.matsimServices.getScenario.getPopulation.getPersons.asScala.map { case (personId, person) =>
      personId -> (person.getSelectedPlan.getPlanElements.asScala
        .find(_.isInstanceOf[Activity])
        .map(_.asInstanceOf[Activity].getEndTime)
        .getOrElse(0.0) + (24 * 3600.0)).toInt
    }.toMap

  private val maybeDebugReport: Option[Cancellable] = if (beamServices.beamConfig.beam.debug.debugEnabled) {
    Some(context.system.scheduler.scheduleWithFixedDelay(10.seconds, 30.seconds, self, DebugReport)(context.dispatcher))
  } else {
    None
  }

  protected val chargingNetworkHelper: ChargingNetworkHelper = ChargingNetworkHelper(chargingNetwork, rideHailNetwork)
  protected val powerController = new PowerController(chargingNetworkHelper, beamConfig)

  protected val sitePowerManager =
    new SitePowerManager(chargingNetworkHelper, powerController.unlimitedPhysicalBounds, beamServices)

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
      chargingNetworkHelper.get(inquiry.reservedFor.managerId).processParkingInquiry(inquiry, true) match {
        case Some(parkingResponse) if parkingResponse.stall.chargingPointType.isDefined =>
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
      val loadEstimate = sitePowerManager.requiredPowerInKWOverNextPlanningHorizon(timeBin)
      val simulatedParkingInquiries = simulateEventsIfScalingEnabled(timeBin, triggerId)
      // obtaining physical bounds
      val physicalBounds = powerController.obtainPowerPhysicalBounds(timeBin, loadEstimate)
      val allConnectedVehicles = chargingNetwork.connectedVehicles ++ rideHailNetwork.connectedVehicles
      val triggers = allConnectedVehicles.par.flatMap { case (_, chargingVehicle) =>
        // Refuel
        handleRefueling(chargingVehicle)
        // Calculate the energy to charge and prepare for next current cycle of charging
        dispatchEnergyAndProcessChargingCycle(
          chargingVehicle,
          timeBin,
          timeBin + beamConfig.beam.agentsim.chargingNetworkManager.timeStepInSeconds,
          physicalBounds,
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
        case Some(ChargingVehicle(_, _, _, _, _, _, _, _, _, _, theSender, _, _)) =>
          theSender ! EndingRefuelSession(tick, vehicle.id, triggerId)
          sender ! CompletionNotice(triggerId)
        case _ =>
          sender ! CompletionNotice(triggerId)
      }

    case request @ ChargingPlugRequest(tick, vehicle, stall, _, triggerId, theSender, _, _) =>
      log.debug(s"ChargingPlugRequest received for vehicle $vehicle at $tick and stall ${vehicle.stall}")
      val responseHasTriggerId = if (vehicle.isEV) {
        // connecting the current vehicle
        val (parkingDuration, activityType) = vehicle2InquiryMap
          .get(vehicle.id)
          .map {
            case ParkingInquiry(_, activityType, _, _, _, _, _, parkingDuration, _, _, searchMode, _, _)
                if searchMode == EnRouteCharging =>
              (parkingDuration, "EnRoute-" + activityType)
            case ParkingInquiry(_, activityType, _, _, _, _, _, parkingDuration, _, _, _, _, _) =>
              (parkingDuration, activityType)
          }
          .getOrElse(
            (
              beamConfig.beam.agentsim.agents.parking.estimatedMinParkingDurationInSeconds,
              ParkingActivityType.Wherever.toString
            )
          )
        chargingNetworkHelper
          .get(stall.reservedFor.managerId)
          .processChargingPlugRequest(request, parkingDuration.toInt, activityType, theSender) map {
          case chargingVehicle if chargingVehicle.chargingStatus.last.status == WaitingAtStation =>
            val numVehicleWaitingToCharge = chargingVehicle.chargingStation.howManyVehiclesAreWaiting
            log.debug(
              s"Vehicle $vehicle is moved to waiting line at $tick in station ${chargingVehicle.chargingStation}, " +
              s"with {} vehicles connected and {} in grace period and {} in waiting line",
              chargingVehicle.chargingStation.howManyVehiclesAreCharging,
              chargingVehicle.chargingStation.howManyVehiclesAreInGracePeriodAfterCharging,
              numVehicleWaitingToCharge
            )
            WaitingToCharge(tick, vehicle.id, numVehicleWaitingToCharge, triggerId)
          case chargingVehicle =>
            vehicle2InquiryMap.remove(vehicle.id)
            handleStartCharging(tick, chargingVehicle, triggerId = triggerId)
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

    case ChargingUnplugRequest(tick, personId, vehicle, triggerId) =>
      log.debug(s"ChargingUnplugRequest received for vehicle $vehicle from plug ${vehicle.stall} at $tick")
      val bounds = powerController.obtainPowerPhysicalBounds(tick)
      val responseHasTriggerId = vehicle.stall match {
        case Some(stall) =>
          chargingNetworkHelper.get(stall.reservedFor.managerId).disconnectVehicle(vehicle.id, tick) match {
            case Some(
                  chargingVehicle @ ChargingVehicle(
                    vehicle,
                    _,
                    station,
                    _,
                    _,
                    personId,
                    _,
                    _,
                    _,
                    _,
                    _,
                    listStatus,
                    sessions
                  )
                ) =>
              if (sessions.nonEmpty && !listStatus.exists(_.status == GracePeriod)) {
                // If the vehicle was still charging
                val unplugTime = currentTimeBin(tick)
                val index = sessions.indexWhere(x => currentTimeBin(x.startTime) == unplugTime && x.startTime <= tick)
                val (startTime, endTime) = if (index == -1) (unplugTime, tick) else (sessions(index).startTime, tick)
                dispatchEnergyAndProcessChargingCycle(chargingVehicle, startTime, endTime, bounds, triggerId, true)
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
                    self,
                    newChargingVehicle.shiftStatus,
                    newChargingVehicle.shiftDuration
                  )
                }
              val (_, totEnergy) = chargingVehicle.calculateChargingSessionLengthAndEnergyInJoule
              UnpluggingVehicle(tick, personId, vehicle, totEnergy, triggerId)
            case _ =>
              log.debug(s"Vehicle $vehicle is already disconnected or unhandled at $tick")
              UnhandledVehicle(tick, personId, vehicle, triggerId)
          }
        case _ =>
          log.debug(s"Cannot unplug $vehicle as it doesn't have a stall at $tick")
          UnhandledVehicle(tick, personId, vehicle, triggerId)
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
      powerController.close()
      context.children.foreach(_ ! Finish)
      context.stop(self)
  }

  protected def getScheduler: ActorRef = scheduler
  protected def getBeamServices: BeamServices = beamServices
  protected def getParkingManager: ActorRef = parkingNetworkManager
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
    sender: ActorRef,
    shiftStatus: ShiftStatus = NotApplicable,
    shiftDuration: Option[Int] = None
  ) extends HasTriggerId

  case class ChargingUnplugRequest(tick: Int, personId: Id[_], vehicle: BeamVehicle, triggerId: Long)
      extends HasTriggerId
  case class StartingRefuelSession(tick: Int, triggerId: Long) extends HasTriggerId
  case class EndingRefuelSession(tick: Int, vehicleId: Id[BeamVehicle], triggerId: Long) extends HasTriggerId

  case class WaitingToCharge(tick: Int, vehicleId: Id[BeamVehicle], numVehicleWaitingToCharge: Int, triggerId: Long)
      extends HasTriggerId
  case class UnhandledVehicle(tick: Int, personId: Id[_], vehicle: BeamVehicle, triggerId: Long) extends HasTriggerId

  case class UnpluggingVehicle(
    tick: Int,
    person: Id[Person],
    vehicle: BeamVehicle,
    energyCharged: Double,
    triggerId: Long
  ) extends HasTriggerId

  def props(
    beamServices: BeamServices,
    chargingNetwork: ChargingNetwork,
    rideHailNetwork: ChargingNetwork,
    parkingNetworkManager: ActorRef,
    scheduler: ActorRef
  ): Props = {
    Props(new ChargingNetworkManager(beamServices, chargingNetwork, rideHailNetwork, parkingNetworkManager, scheduler))
  }

  case class ChargingNetworkHelper(chargingNetwork: ChargingNetwork, rideHailNetwork: ChargingNetwork) {

    lazy val allChargingStations: List[ChargingStation] =
      chargingNetwork.chargingStations.filter(_.zone.chargingPointType.isDefined) ++ rideHailNetwork.chargingStations
        .filter(_.zone.chargingPointType.isDefined)

    /**
      * @param managerId vehicle manager id
      * @return
      */
    def get(managerId: Id[VehicleManager]): ChargingNetwork = {
      get(VehicleManager.getReservedFor(managerId).get)
    }

    /**
      * @param reservedFor ReservedFor
      * @return
      */
    def get(reservedFor: ReservedFor): ChargingNetwork = {
      reservedFor.managerType match {
        case VehicleManager.TypeEnum.RideHail => rideHailNetwork
        case _                                => chargingNetwork
      }
    }
  }
}
