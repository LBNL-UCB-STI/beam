package beam.agentsim.agents.household

import akka.actor.ActorRef
import akka.actor.Status.{Failure, Success}
import akka.pattern.pipe
import akka.util.Timeout
import beam.agentsim.Resource.NotifyVehicleIdle
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.agents.freight.input.FreightReader
import beam.agentsim.agents.household.HouseholdActor._
import beam.agentsim.agents.household.HouseholdFleetManager.ResolvedParkingResponses
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.ActualVehicle
import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, VehicleManager}
import beam.agentsim.events.{ParkingEvent, SpaceTime}
import beam.agentsim.infrastructure.ChargingNetworkManager._
import beam.agentsim.infrastructure.ParkingInquiry.{ParkingActivityType, ParkingSearchMode}
import beam.agentsim.infrastructure.{ParkingInquiry, ParkingInquiryResponse}
import beam.agentsim.scheduler.BeamAgentScheduler.CompletionNotice
import beam.agentsim.scheduler.HasTriggerId
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.sim.config.BeamConfig.Beam.Debug
import beam.utils.logging.pattern.ask
import beam.utils.logging.{ExponentialLazyLogging, LoggingMessageActor}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.core.api.experimental.events.EventsManager

import java.util.concurrent.TimeUnit
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

class HouseholdFleetManager(
  parkingManager: ActorRef,
  chargingNetworkManager: ActorRef,
  vehicles: Map[Id[BeamVehicle], BeamVehicle],
  householdMembersToActivityTypeAndLocation: Map[Id[Person], ActivityTypeAndLocation],
  maybeEmergencyHouseholdVehicleGenerator: Option[EmergencyHouseholdVehicleGenerator],
  whoDrivesThisFreightVehicle: Map[Id[BeamVehicle], Id[Person]], // so far only freight module is using this collection
  eventsManager: EventsManager,
  geo: GeoUtils,
  beamConfig: BeamConfig,
  implicit val debug: Debug
) extends LoggingMessageActor
    with ExponentialLazyLogging {
  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)
  private implicit val executionContext: ExecutionContext = context.dispatcher
  private var nextVehicleIndex = 0

  private val vehiclesInternal: collection.mutable.Map[Id[BeamVehicle], BeamVehicle] = mutable.Map.empty ++ vehicles
  private lazy val vehicleTypes: Set[BeamVehicleType] = vehicles.values.map(_.beamVehicleType).toSet

  private lazy val availableVehicleCategories: Set[VehicleCategory] =
    vehicles.values.map(_.beamVehicleType.vehicleCategory).toSet
  private val availableVehicles: mutable.Set[BeamVehicle] = mutable.Set.empty
  var triggerSender: Option[ActorRef] = None

  private val trackingVehicleAssignmentAtInitialization = mutable.HashMap.empty[Id[BeamVehicle], Id[Person]]
  private val isFreightCarrier: Boolean = whoDrivesThisFreightVehicle.nonEmpty

  override def loggedReceive: Receive = {
    case ResolvedParkingResponses(triggerId, xs) =>
      logger.debug(s"ResolvedParkingResponses ($triggerId, $xs)")
      xs.foreach { case (id, resp) =>
        if (id.toString.startsWith("ft-a29626")) {
          logger.info(s"Received ResolvedParkingResponses for personId: ${id}")
        }
        val veh = vehiclesInternal(id)
        val person = trackingVehicleAssignmentAtInitialization(id)
        veh.setManager(Some(self))
        veh.spaceTime = SpaceTime(resp.stall.locationUTM.getX, resp.stall.locationUTM.getY, 0)
        veh.setMustBeDrivenHome(false)
        veh.useParkingStall(resp.stall)
        val parkEvent = ParkingEvent(
          time = 0,
          stall = resp.stall,
          locationWGS = geo.utm2Wgs(resp.stall.locationUTM),
          vehicleId = id,
          driverId = person.toString
        )
        eventsManager.processEvent(parkEvent)
        if (resp.stall.chargingPointType.isDefined) {
          chargingNetworkManager ! ChargingPlugRequest(
            0,
            veh,
            resp.stall,
            // use first household member id as stand-in.
            trackingVehicleAssignmentAtInitialization(id),
            triggerId,
            self
          )
        }

        self ! ReleaseVehicleAndReply(veh, triggerId = triggerId)
      }
      triggerSender.foreach(actorRef => actorRef ! CompletionNotice(triggerId, Vector()))

    case TriggerWithId(InitializeTrigger(tick), triggerId) =>
      triggerSender = Some(sender())
      val listOfFutures: List[Future[(Id[BeamVehicle], ParkingInquiryResponse)]] = {
        // Request that all household vehicles be parked at the home coordinate. If the vehicle is an EV,
        // send the request to the charging manager. Otherwise send request to the parking manager.
        val workingPersonsList = householdMembersToActivityTypeAndLocation
          .filter(_._2.parkingActivityType == ParkingActivityType.Working)
          .keys
          .toBuffer
        vehicles.map { case (id, vehicle) =>
          val personId: Id[Person] = {
            if (isFreightCarrier) {
              householdMembersToActivityTypeAndLocation
                .find(_._2.parkingActivityType == ParkingActivityType.Freight)
                .map(_._1)
                .getOrElse {
                  householdMembersToActivityTypeAndLocation.foreach { case (personId, location) =>
                    logger.error(s"Person ID: $personId")
                    logger.error(s"  Parking Activity Type: ${location.parkingActivityType}")
                    logger.error(s"  Activity Type: ${location.activityType}")
                    logger.error(s"  Activity Location: ${location.activityLocation}")
                    logger.error(s"  Activity End Time: ${location.activityEndTime}")
                    logger.error("---")
                  }
                  throw new RuntimeException(
                    s"Freight vehicle ${vehicle.id} has no assigned person with Freight parking activity"
                  )
                }
            } else if (workingPersonsList.isEmpty) {
              householdMembersToActivityTypeAndLocation
                .find(_._2.parkingActivityType == ParkingActivityType.Home)
                .map(_._1)
                .getOrElse(householdMembersToActivityTypeAndLocation.keys.head)
            } else workingPersonsList.remove(0)
          }
          trackingVehicleAssignmentAtInitialization.put(vehicle.id, personId)
          val ActivityTypeAndLocation(_, activityType, location, endTime) =
            householdMembersToActivityTypeAndLocation(personId)
          val inquiry = ParkingInquiry.init(
            SpaceTime(location, 0),
            activityType,
            VehicleManager.getReservedFor(vehicle.vehicleManagerId.get).get,
            personId = Option(personId),
            beamVehicle = Option(vehicle),
            triggerId = triggerId,
            searchMode = ParkingSearchMode.Init,
            parkingDuration = endTime - tick
          )
          if (vehicle.isEV && beamConfig.beam.agentsim.chargingNetworkManager.overnightChargingEnabled) {
            logger.debug(s"Overnight charging vehicle $vehicle with state of charge ${vehicle.getStateOfCharge}")
            (chargingNetworkManager ? inquiry).mapTo[ParkingInquiryResponse].map(r => (id, r))
          } else {
            logger.debug(s"Overnight parking vehicle $vehicle")
            (parkingManager ? inquiry).mapTo[ParkingInquiryResponse].map(r => (id, r))
          }
        }.toList
      }
      val futureOfList = Future.sequence(listOfFutures)
      val response = futureOfList.map(ResolvedParkingResponses(triggerId, _))
      response.pipeTo(self)

    case NotifyVehicleIdle(vId, _, whenWhere, _, _, _, _) =>
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
        availableVehicles += vehicle
        logger.debug("Vehicle {} is now available", vehicle.id)
      }

    case ReleaseVehicleAndReply(vehicle, _, _) =>
      vehicle.unsetDriver()
      if (availableVehicles.contains(vehicle)) {
        sender ! Failure(new RuntimeException(s"I can't release vehicle ${vehicle.id} because I have it already"))
      } else {
        if (self.actorRef.path.parent.name != vehicle.getManager.get.path.parent.name) {
          logger.warn(
            s"Removing vehicle ${vehicle.id} from household vehicle manager " +
            s"${self.actorRef.path.parent.name} because I'm not its manager"
          )
        } else {
          if (vehicle.id.toString.startsWith("ft-a29626")) {
            logger.info(s"Received ReleaseVehicleAndReply for personId: ${vehicle.id}")
          }
          availableVehicles += vehicle
          logger.debug("Vehicle {} is now available", vehicle.id)
        }
        sender() ! Success
      }

    case GetVehicleTypes(triggerId) =>
      sender() ! VehicleTypesResponse(vehicleTypes, triggerId)

    case inquiry @ MobilityStatusInquiry(personId, _, _, requireVehicleCategoryAvailable, triggerId) =>
      val availableVehicleMaybe: Option[BeamVehicle] = requireVehicleCategoryAvailable match {
        case Some(requireVehicleCategory) =>
          availableVehicles.find(_.beamVehicleType.vehicleCategory == requireVehicleCategory)
        case None if personId.toString.startsWith(FreightReader.FREIGHT_ID_PREFIX) =>
          if (personId.toString.startsWith("ft-a29626")) {
            logger.info(s"Received MobilityStatusInquiry for personId: ${personId}")
          }
          val assignedVehicleId = whoDrivesThisFreightVehicle.collectFirst { case (vehicleId, `personId`) => vehicleId }
          availableVehicles.find(v => assignedVehicleId.contains(v.id))
        case _ => availableVehicles.headOption
      }

      availableVehicleMaybe match {
        case Some(availableVehicle) =>
          logger.debug("Vehicle {} is now taken", availableVehicle.id)
          availableVehicle.becomeDriver(sender)
          sender() ! MobilityStatusResponse(Vector(ActualVehicle(availableVehicle)), triggerId)
          if (personId.toString.startsWith("ft-a29626")) {
            logger.info(s"Removing vehicle ${availableVehicle.id} but we are still in MobilityStatusInquiry section")
          }
          availableVehicles -= availableVehicle
        case None if createAnEmergencyVehicle(inquiry).nonEmpty =>
          logger.debug(s"An emergency vehicle has been created!")
        case _ =>
          if (availableVehicles.isEmpty) {
            requireVehicleCategoryAvailable match {
              case Some(requiredType) if availableVehicleCategories.contains(requiredType) =>
                logger.warn(s"Emergency vehicle generation for type $requiredType failed")
              case Some(_) =>
                logger.debug(s"Ignoring vehicle request because it isn't for the right category")
              case None if personId.toString.startsWith(FreightReader.FREIGHT_ID_PREFIX) =>
                logger.warn(s"Emergency vehicle generation for person $personId failed")
              case None =>
            }

          }
          sender() ! MobilityStatusResponse(Vector(), triggerId)
      }

    case pir: ParkingInquiryResponse =>
      logger.error(s"STUCK with ParkingInquiryResponse: $pir")
    case e: StartingRefuelSession =>
      logger.debug("HouseholdFleetManager.StartingRefuelSession: {}", e)
    case e: UnhandledVehicle =>
      logger.error("HouseholdFleetManager.UnhandledVehicle: {}", e)
    case e: WaitingToCharge =>
      logger.debug("HouseholdFleetManager.WaitingInLine: {}", e)
    case e: EndingRefuelSession =>
      logger.debug("HouseholdFleetManager.EndingRefuelSession: {}", e)
    case Finish =>
      context.stop(self)
    case Success =>
    case x =>
      logger.error(s"No handler for $x")
  }

  /**
    * @param inquiry MobilityStatusInquiry
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
      logger.warn(
        s"No vehicles available for category $category available for " +
        s"person ${inquiry.personId.toString} in available vehicles $availableVehicles" +
        s", creating a new vehicle with id ${vehicle.id.toString}"
      )

      // Create a vehicle out of thin air
      nextVehicleIndex += 1
      val mobilityRequester = sender()
      vehiclesInternal(vehicle.id) = vehicle

      // Pipe my car through the parking manager
      // and complete initialization only when I got them all.
      val reservedFor = VehicleManager.getReservedFor(vehicle.vehicleManagerId.get()).get
      val activityType = if (reservedFor.managerType == VehicleManager.TypeEnum.Freight) {
        ParkingActivityType.Freight.toString
      } else {
        ParkingActivityType.Miscellaneous.toString
      }
      val responseFuture = parkingManager ? ParkingInquiry.init(
        inquiry.whereWhen,
        activityType,
        VehicleManager.getReservedFor(vehicle.vehicleManagerId.get()).get,
        Some(vehicle),
        triggerId = inquiry.triggerId,
        parkingDuration = beamConfig.beam.agentsim.agents.parking.estimatedMinParkingDurationInSeconds
      )

      responseFuture.collect { case ParkingInquiryResponse(stall, _, otherTriggerId) =>
        vehicle.setMustBeDrivenHome(false)
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
  import akka.actor.{ActorRef, Props}

  def props(
    parkingManager: ActorRef,
    chargingNetworkManager: ActorRef,
    vehiclesInCategory: Map[Id[BeamVehicle], BeamVehicle],
    householdMembersToActivityTypeAndLocation: Map[Id[Person], ActivityTypeAndLocation],
    emergencyGenerator: Option[EmergencyHouseholdVehicleGenerator],
    whoDrivesThisFreightVehicle: Map[Id[BeamVehicle], Id[Person]],
    events: EventsManager,
    geo: GeoUtils,
    beamConfig: BeamConfig,
    debug: Debug
  ): Props = {
    Props(
      new HouseholdFleetManager(
        parkingManager,
        chargingNetworkManager,
        vehiclesInCategory,
        householdMembersToActivityTypeAndLocation,
        emergencyGenerator,
        whoDrivesThisFreightVehicle,
        events,
        geo,
        beamConfig,
        debug
      )
    )
  }

  case class ResolvedParkingResponses(triggerId: Long, xs: List[(Id[BeamVehicle], ParkingInquiryResponse)])
      extends HasTriggerId
}
