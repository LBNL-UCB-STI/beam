package beam.agentsim.agents.household

import akka.actor.ActorRef
import akka.actor.Status.{Failure, Success}
import akka.pattern.pipe
import akka.util.Timeout
import beam.agentsim.Resource.NotifyVehicleIdle
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.agents.household.HouseholdActor._
import beam.agentsim.agents.household.HouseholdFleetManager.ResolvedParkingResponses
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.ActualVehicle
import beam.agentsim.agents.vehicles.{BeamVehicle, VehicleManager}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.ChargingNetworkManager._
import beam.agentsim.infrastructure.ParkingInquiry.{ParkingActivityType, ParkingSearchMode}
import beam.agentsim.infrastructure.{ParkingInquiry, ParkingInquiryResponse}
import beam.agentsim.scheduler.BeamAgentScheduler.CompletionNotice
import beam.agentsim.scheduler.HasTriggerId
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.sim.config.BeamConfig.Beam.Debug
import beam.utils.logging.pattern.ask
import beam.utils.logging.{ExponentialLazyLogging, LoggingMessageActor}
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}

import java.util.concurrent.TimeUnit
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

class HouseholdFleetManager(
  parkingManager: ActorRef,
  chargingNetworkManager: ActorRef,
  vehicles: Map[Id[BeamVehicle], BeamVehicle],
  homeAndStartingWorkLocations: Map[Id[Person], (ParkingActivityType, String, Coord)],
  maybeEmergencyHouseholdVehicleGenerator: Option[EmergencyHouseholdVehicleGenerator]
)(implicit val debug: Debug)
    extends LoggingMessageActor
    with ExponentialLazyLogging {
  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)
  private implicit val executionContext: ExecutionContext = context.dispatcher
  private var nextVehicleIndex = 0

  private val vehiclesInternal: collection.mutable.Map[Id[BeamVehicle], BeamVehicle] =
    collection.mutable.Map(vehicles.toSeq: _*)

  private var availableVehicles: List[BeamVehicle] = Nil
  var triggerSender: Option[ActorRef] = None

  private val trackingVehicleAssignmentAtInitialization = mutable.HashMap.empty[Id[BeamVehicle], Id[Person]]

  override def loggedReceive: Receive = {
    case ResolvedParkingResponses(triggerId, xs) =>
      logger.debug(s"ResolvedParkingResponses ($triggerId, $xs)")
      xs.foreach { case (id, resp) =>
        val veh = vehiclesInternal(id)
        veh.setManager(Some(self))
        veh.spaceTime = SpaceTime(resp.stall.locationUTM.getX, resp.stall.locationUTM.getY, 0)
        veh.setMustBeDrivenHome(true)
        veh.useParkingStall(resp.stall)
        if (resp.stall.chargingPointType.isDefined) {
          chargingNetworkManager ! ChargingPlugRequest(
            0,
            veh,
            resp.stall,
            // use first household member id as stand-in.
            trackingVehicleAssignmentAtInitialization(id),
            triggerId
          )
        }

        self ! ReleaseVehicleAndReply(veh, triggerId = triggerId)
      }
      triggerSender.foreach(actorRef => actorRef ! CompletionNotice(triggerId, Vector()))

    case TriggerWithId(InitializeTrigger(_), triggerId) =>
      triggerSender = Some(sender())
      val listOfFutures: List[Future[(Id[BeamVehicle], ParkingInquiryResponse)]] = {
        // Request that all household vehicles be parked at the home coordinate. If the vehicle is an EV,
        // send the request to the charging manager. Otherwise send request to the parking manager.
        val workingPersonsList = homeAndStartingWorkLocations.filter(_._2._1 == ParkingActivityType.Work).keys.toBuffer
        vehicles.toList.map { case (id, vehicle) =>
          val personId: Id[Person] =
            if (workingPersonsList.nonEmpty) workingPersonsList.remove(0)
            else
              homeAndStartingWorkLocations
                .find(_._2._1 == ParkingActivityType.Home)
                .map(_._1)
                .getOrElse(homeAndStartingWorkLocations.keys.head)
          trackingVehicleAssignmentAtInitialization.put(vehicle.id, personId)
          val (_, activityType, location) = homeAndStartingWorkLocations(personId)
          val inquiry = ParkingInquiry.init(
            SpaceTime(location, 0),
            activityType,
            VehicleManager.getReservedFor(vehicle.vehicleManagerId.get).get,
            beamVehicle = Option(vehicle),
            triggerId = triggerId,
            searchMode = ParkingSearchMode.Init
          )
          if (vehicle.isEV) (chargingNetworkManager ? inquiry).mapTo[ParkingInquiryResponse].map(r => (id, r))
          else (parkingManager ? inquiry).mapTo[ParkingInquiryResponse].map(r => (id, r))
        }
      }
      val futureOfList = Future.sequence(listOfFutures)
      val response = futureOfList.map(ResolvedParkingResponses(triggerId, _))
      response.pipeTo(self)

    case NotifyVehicleIdle(vId, whenWhere, _, _, _, _) =>
      val vehId = vId.asInstanceOf[Id[BeamVehicle]]
      vehiclesInternal.get(vehId) match {
        case Some(vehicle) =>
          vehicle.spaceTime = whenWhere
          logger.debug("updated vehicle {} with location {}", vehId, whenWhere)
        case _ =>
          logger.error("Why is not this vehicle {} at location {} yet!", vehId, whenWhere)
      }

    case ReleaseVehicle(vehicle, _) =>
      vehicle.unsetDriver()
      if (availableVehicles.contains(vehicle)) {
        logger.warn("I can't release vehicle {} because I have it already", vehicle.id)
      } else {
        availableVehicles = vehicle :: availableVehicles
        logger.debug("Vehicle {} is now available", vehicle.id)
      }

    case ReleaseVehicleAndReply(vehicle, _, _) =>
      vehicle.unsetDriver()
      if (availableVehicles.contains(vehicle)) {
        sender ! Failure(new RuntimeException(s"I can't release vehicle ${vehicle.id} because I have it already"))
      } else {
        availableVehicles = vehicle :: availableVehicles
        logger.debug("Vehicle {} is now available", vehicle.id)
        sender() ! Success
      }

    case GetVehicleTypes(triggerId) =>
      sender() ! VehicleTypesResponse(vehicles.values.map(_.beamVehicleType).toSet, triggerId)

    case MobilityStatusInquiry(personId, whenWhere, _, requireVehicleCategoryAvailable, triggerId) =>
      {
        for {
          neededVehicleCategory              <- requireVehicleCategoryAvailable
          emergencyHouseholdVehicleGenerator <- maybeEmergencyHouseholdVehicleGenerator
          vehicle <- emergencyHouseholdVehicleGenerator.createVehicle(
            personId,
            nextVehicleIndex,
            neededVehicleCategory,
            whenWhere,
            self
          )
        } yield {
          if (availableVehicles.isEmpty) {
            // Create a vehicle out of thin air
            nextVehicleIndex += 1
            val mobilityRequester = sender()
            vehiclesInternal(vehicle.id) = vehicle

            // Pipe my car through the parking manager
            // and complete initialization only when I got them all.
            val responseFuture = parkingManager ? ParkingInquiry.init(
              whenWhere,
              "wherever",
              triggerId = triggerId
            )
            logger.warn(
              s"No vehicles available for category ${neededVehicleCategory} available for person ${personId.toString}, creating a new vehicle with id ${vehicle.id.toString}"
            )

            responseFuture.collect { case ParkingInquiryResponse(stall, _, otherTriggerId) =>
              vehicle.useParkingStall(stall)
              logger.debug("Vehicle {} is now taken, which was just created", vehicle.id)
              vehicle.becomeDriver(mobilityRequester)
              MobilityStatusResponse(Vector(ActualVehicle(vehicle)), otherTriggerId)
            } pipeTo mobilityRequester
          } else {
            availableVehicles = availableVehicles match {
              case firstVehicle :: rest =>
                logger.debug("Vehicle {} is now taken", firstVehicle.id)
                firstVehicle.becomeDriver(sender)
                sender() ! MobilityStatusResponse(Vector(ActualVehicle(firstVehicle)), triggerId)
                rest
              case _ =>
                logger.error(s"THE LIST OF VEHICLES SHOULDN'T BE EMPTY")
                Nil
            }
          }
        }
      }.getOrElse {
        availableVehicles = availableVehicles match {
          case firstVehicle :: rest =>
            logger.debug("Vehicle {} is now taken", firstVehicle.id)
            firstVehicle.becomeDriver(sender)
            sender() ! MobilityStatusResponse(Vector(ActualVehicle(firstVehicle)), triggerId)
            rest
          case Nil =>
            logger.debug(s"Not returning vehicle because no default is defined")
            sender() ! MobilityStatusResponse(Vector(), triggerId)
            Nil
        }
      }

    case pir: ParkingInquiryResponse =>
      logger.error(s"STUCK with ParkingInquiryResponse: $pir")
    case e @ StartingRefuelSession(_, _) =>
      logger.debug("HouseholdFleetManager.StartingRefuelSession: {}", e)
    case e @ UnhandledVehicle(_, _, _) =>
      logger.debug("HouseholdFleetManager.UnhandledVehicle: {}", e)
    case e @ WaitingToCharge(_, _, _) =>
      logger.debug("HouseholdFleetManager.WaitingInLine: {}", e)
    case e @ EndingRefuelSession(_, _, triggerId) =>
      logger.debug("HouseholdFleetManager.EndingRefuelSession: {}", e)
      triggerSender.get ! CompletionNotice(triggerId)
    case Finish =>
      context.stop(self)
    case Success =>
    case x =>
      logger.warn(s"No handler for $x")
  }
}

object HouseholdFleetManager {

  case class ResolvedParkingResponses(triggerId: Long, xs: List[(Id[BeamVehicle], ParkingInquiryResponse)])
      extends HasTriggerId
}
