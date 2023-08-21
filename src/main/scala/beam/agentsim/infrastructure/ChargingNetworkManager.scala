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
import beam.agentsim.infrastructure.ParkingInquiry.ParkingSearchMode._
import beam.agentsim.infrastructure.parking.ParkingZoneId
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
        .flatMap(x => Option(x.asInstanceOf[Activity].getEndTime.orElse(null)))
        .getOrElse(0.0) + (24 * 3600.0)).toInt
    }.toMap

  private val maybeDebugReport: Option[Cancellable] = if (beamServices.beamConfig.beam.debug.debugEnabled) {
    Some(context.system.scheduler.scheduleWithFixedDelay(10.seconds, 30.seconds, self, DebugReport)(context.dispatcher))
  } else {
    None
  }

  protected val chargingNetworkHelper: ChargingNetworkHelper = ChargingNetworkHelper(chargingNetwork, rideHailNetwork)
  protected val sitePowerManager = new SitePowerManager(chargingNetworkHelper, beamServices)

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
      val chargingNetwork = chargingNetworkHelper.get(inquiry.reservedFor.managerId)
      val response = chargingNetwork.processParkingInquiry(inquiry)
      if (inquiry.reserveStall && List(DestinationCharging, EnRouteCharging).contains(inquiry.searchMode))
        collectChargingRequests(inquiry, response.stall)
      sender() ! response

    case TriggerWithId(InitializeTrigger(_), triggerId) =>
      log.info("ChargingNetworkManager is Starting!")
      Future(scheduler ? ScheduleTrigger(PlanEnergyDispatchTrigger(0), self))
        .map(_ => CompletionNotice(triggerId, Vector()))
        .pipeTo(sender())

    case TriggerWithId(PlanEnergyDispatchTrigger(timeBin), triggerId) =>
      val s = System.currentTimeMillis
      log.debug(s"Planning energy dispatch for vehicles currently connected to a charging point, at t=$timeBin")
      sitePowerManager.obtainPowerCommandsAndLimits(timeBin)
      val simulatedParkingInquiries = simulateEventsIfScalingEnabled(timeBin, triggerId)
      // obtaining physical bounds
      val triggers = chargingNetworkHelper.allChargingStations.flatMap(_._2.vehiclesCurrentlyCharging).par.flatMap {
        case (_, chargingVehicle) =>
          // Refuel
          handleRefueling(chargingVehicle)
          // Calculate the energy to charge and prepare for next current cycle of charging
          dispatchEnergyAndProcessChargingCycle(
            chargingVehicle,
            timeBin,
            timeBin + beamConfig.beam.agentsim.chargingNetworkManager.timeStepInSeconds
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

    case TriggerWithId(ChargingTimeOutTrigger(tick, chargingVehicle), triggerId) =>
      log.debug(s"ChargingTimeOutTrigger for vehicle ${chargingVehicle.vehicle.id} at $tick")
      val vehicleEndedCharging = handleEndCharging(tick, chargingVehicle)
      vehicleEndedCharging.foreach(_.theSender ! EndingRefuelSession(tick, chargingVehicle.vehicle.id, triggerId))
      // Do not send completion notice to vehicles in EnRoute mode
      // Since they need to process additional tasks before completing
      // Maybe for ride hail too !?
      if (!vehicleEndedCharging.exists(charging => charging.isInEnRoute || charging.vehicle.isRideHail))
        sender ! CompletionNotice(triggerId)

    case request @ ChargingPlugRequest(tick, vehicle, stall, personId, triggerId, theSender, _, _) =>
      log.debug(s"ChargingPlugRequest received from vehicle $vehicle at $tick and stall ${vehicle.stall}")
      val responseHasTriggerId = if (vehicle.isEV) {
        // connecting the current vehicle
        val chargingNetwork = chargingNetworkHelper.get(stall.reservedFor.managerId)
        chargingNetwork
          .processChargingPlugRequest(
            request,
            beamConfig.beam.agentsim.agents.parking.estimatedMinParkingDurationInSeconds.toInt,
            chargingEndTimeInSeconds.get(personId),
            theSender
          ) map {
          case chargingVehicle if chargingVehicle.chargingStatus.last.status == WaitingAtStation =>
            val numVehicleWaitingToCharge = chargingVehicle.chargingStation.howManyVehiclesAreWaiting
            log.debug(
              s"Vehicle $vehicle is moved to waiting line at $tick in station " +
              s"${chargingVehicle.chargingStation}, with {} vehicles connected and {} " +
              s"in grace period and {} in waiting line",
              chargingVehicle.chargingStation.howManyVehiclesAreCharging,
              chargingVehicle.chargingStation.howManyVehiclesAreInGracePeriodAfterCharging,
              numVehicleWaitingToCharge
            )
            WaitingToCharge(tick, vehicle.id, stall, triggerId)
          case chargingVehicle =>
            chargingVehicle.vehicle.useParkingStall(stall)
            handleStartCharging(tick, chargingVehicle)
            StartingRefuelSession(tick, chargingVehicle, stall, triggerId)
        } getOrElse Failure(
          new RuntimeException(
            s"Cannot find a ${request.stall.reservedFor} station identified with tazId ${request.stall.tazId}, " +
            s"parkingType ${request.stall.parkingType} and chargingPointType ${request.stall.chargingPointType.get}!"
          )
        )
      } else {
        Failure(new RuntimeException(s"$vehicle is not a BEV/PHEV vehicle. Request sent by agent ${sender.path.name}"))
      }
      theSender ! responseHasTriggerId

    case ChargingUnplugRequest(tick, personId, vehicle, triggerId) =>
      log.debug(s"ChargingUnplugRequest received for vehicle $vehicle from plug ${vehicle.stall} at $tick")
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
                    _,
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
                dispatchEnergyAndProcessChargingCycle(chargingVehicle, startTime, endTime, interruptCharging = true)
                handleEndCharging(endTime, chargingVehicle)
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
                    newChargingVehicle.theSender,
                    newChargingVehicle.shiftStatus,
                    newChargingVehicle.shiftDuration
                  )
                }
              val (_, totEnergy) = chargingVehicle.calculateChargingSessionLengthAndEnergyInJoule
              UnpluggingVehicle(tick, personId, vehicle, stall, totEnergy)
            case _ =>
              log.debug(s"Vehicle $vehicle is already disconnected or unhandled at $tick")
              UnhandledVehicle(tick, personId, vehicle, Some(stall))
          }
        case _ =>
          log.debug(s"Cannot unplug $vehicle as it doesn't have a stall at $tick")
          UnhandledVehicle(tick, personId, vehicle, None)
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

    case e =>
      log.error(s"unhandled message: $e")
  }

  protected def getScheduler: ActorRef = scheduler
  protected def getBeamServices: BeamServices = beamServices
  protected def getParkingManager: ActorRef = parkingNetworkManager
}

object ChargingNetworkManager extends LazyLogging {

  object DebugReport
  case class PlanEnergyDispatchTrigger(tick: Int) extends Trigger
  case class ChargingTimeOutTrigger(tick: Int, chargingVehicle: ChargingVehicle) extends Trigger

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

  case class StartingRefuelSession(tick: Int, chargingVehicle: ChargingVehicle, stall: ParkingStall, triggerId: Long)
      extends HasTriggerId

  case class WaitingToCharge(tick: Int, vehicleId: Id[BeamVehicle], stall: ParkingStall, triggerId: Long)
      extends HasTriggerId

  case class EndingRefuelSession(tick: Int, vehicleId: Id[BeamVehicle], triggerId: Long) extends HasTriggerId

  case class UnhandledVehicle(tick: Int, personId: Id[_], vehicle: BeamVehicle, stallMaybe: Option[ParkingStall])
      extends Trigger

  case class UnpluggingVehicle(tick: Int, person: Id[_], vehicle: BeamVehicle, stall: ParkingStall, energy: Double)
      extends Trigger

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

    lazy val allChargingStations: Map[Id[ParkingZoneId], ChargingStation] =
      chargingNetwork.chargingStations ++ rideHailNetwork.chargingStations

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
