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
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.events.SpaceTime
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
import scala.concurrent.{ExecutionContext, Future}

class HouseholdFleetManager(
  parkingManager: ActorRef,
  vehicles: Map[Id[BeamVehicle], BeamVehicle],
  homeCoord: Coord,
  maybeEmergencyHouseholdVehicleGenerator: Option[EmergencyHouseholdVehicleGenerator],
  whoDrivesThisVehicle: Map[Id[BeamVehicle], Id[Person]], // so far only freight module is using this collection
  implicit val debug: Debug
) extends LoggingMessageActor
    with ExponentialLazyLogging {
  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)
  private implicit val executionContext: ExecutionContext = context.dispatcher
  private var nextVehicleIndex = 0

  private val vehiclesInternal: collection.mutable.Map[Id[BeamVehicle], BeamVehicle] =
    collection.mutable.Map(vehicles.toSeq: _*)

  private var availableVehicles: List[BeamVehicle] = Nil
  var triggerSender: Option[ActorRef] = None

  override def loggedReceive: Receive = {
    case ResolvedParkingResponses(triggerId, xs) =>
      logger.debug(s"ResolvedParkingResponses ($triggerId, $xs)")
      xs.foreach { case (id, resp) =>
        val veh = vehiclesInternal(id)
        veh.setManager(Some(self))
        veh.spaceTime = SpaceTime(resp.stall.locationUTM.getX, resp.stall.locationUTM.getY, 0)
        veh.setMustBeDrivenHome(true)
        veh.useParkingStall(resp.stall)
        self ! ReleaseVehicleAndReply(veh, triggerId = triggerId)
      }
      triggerSender.foreach(actorRef => actorRef ! CompletionNotice(triggerId, Vector()))

    case TriggerWithId(InitializeTrigger(_), triggerId) =>
      triggerSender = Some(sender())
      val listOfFutures: List[Future[(Id[BeamVehicle], ParkingInquiryResponse)]] = vehicles.toList.map { case (id, _) =>
        (parkingManager ? ParkingInquiry.init(
          SpaceTime(homeCoord, 0),
          "init",
          triggerId = triggerId
        ))
          .mapTo[ParkingInquiryResponse]
          .map { r =>
            (id, r)
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

    case inquiry @ MobilityStatusInquiry(personId, _, _, requireVehicleCategoryAvailable, triggerId) =>
      val availableVehicleMaybe: Option[BeamVehicle] = requireVehicleCategoryAvailable match {
        case Some(_) if personId.toString.contains("freight") =>
          whoDrivesThisVehicle
            .filter(_._2 == personId)
            .flatMap { case (vehicleId, _) => availableVehicles.find(_.id == vehicleId) }
            .headOption
        case Some(requireVehicleCategory) =>
          availableVehicles.find(_.beamVehicleType.vehicleCategory == requireVehicleCategory)
        case _ => availableVehicles.headOption
      }

      availableVehicleMaybe match {
        case Some(availableVehicle) =>
          logger.debug("Vehicle {} is now taken", availableVehicle.id)
          availableVehicle.becomeDriver(sender)
          sender() ! MobilityStatusResponse(Vector(ActualVehicle(availableVehicle)), triggerId)
          availableVehicles = availableVehicles.filter(_ != availableVehicle)
        case None if createAnEmergencyVehicle(inquiry).nonEmpty =>
          logger.debug(s"An emergency vehicle has been created!")
        case _ =>
          if (availableVehicles.isEmpty)
            logger.error(s"THE LIST OF VEHICLES SHOULD NOT BE EMPTY")
          logger.debug(s"Not returning vehicle because no default for  is defined")
          sender() ! MobilityStatusResponse(Vector(), triggerId)
      }

    case Finish =>
      context.stop(self)

    case Success =>
    case pir: ParkingInquiryResponse =>
      logger.error(s"STUCK with ParkingInquiryResponse: $pir")

    case x =>
      logger.warn(s"No handler for $x")
  }

  /**
    * @param inquiry
    * @return
    */
  private def createAnEmergencyVehicle(inquiry: MobilityStatusInquiry): Option[BeamVehicle] = {
    for {
      category    <- inquiry.requireVehicleCategoryAvailable
      emergency   <- maybeEmergencyHouseholdVehicleGenerator
      vehicleType <- emergency.sampleVehicleTypeForEmergencyUse(inquiry.personId, category, inquiry.whereWhen)
    } yield {
      val vehicle = emergency.createAndAddVehicle(
        vehicleType,
        inquiry.personId,
        nextVehicleIndex,
        inquiry.whereWhen,
        self
      )
      // Create a vehicle out of thin air
      nextVehicleIndex += 1
      val mobilityRequester = sender()
      vehiclesInternal(vehicle.id) = vehicle

      // Pipe my car through the parking manager
      // and complete initialization only when I got them all.
      val responseFuture = parkingManager ? ParkingInquiry.init(
        inquiry.whereWhen,
        "wherever",
        triggerId = inquiry.triggerId
      )
      logger.warn(
        s"No vehicles available for category ${category} available for " +
        s"person ${inquiry.personId.toString}, creating a new vehicle with id ${vehicle.id.toString}"
      )

      responseFuture.collect { case ParkingInquiryResponse(stall, _, otherTriggerId) =>
        vehicle.useParkingStall(stall)
        logger.debug("Vehicle {} is now taken, which was just created", vehicle.id)
        vehicle.becomeDriver(mobilityRequester)
        MobilityStatusResponse(Vector(ActualVehicle(vehicle)), otherTriggerId)
      } pipeTo mobilityRequester
      vehicle
    }
  }
}

object HouseholdFleetManager {

  case class ResolvedParkingResponses(triggerId: Long, xs: List[(Id[BeamVehicle], ParkingInquiryResponse)])
      extends HasTriggerId
}
