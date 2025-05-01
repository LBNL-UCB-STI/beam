package beam.agentsim.agents.modalbehaviors

import akka.actor.{ActorRef, FSM}
import akka.pattern.pipe
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents._
import beam.agentsim.agents.household.HouseholdActor.{
  MobilityStatusInquiry,
  MobilityStatusResponse,
  ReleaseVehicle,
  RetryModeChoice
}
import beam.agentsim.agents.modalbehaviors.ChoosesMode._
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.{ActualVehicle, Token, VehicleOrToken}
import beam.agentsim.agents.planning.Strategy.{TourModeChoiceStrategy, TripModeChoiceStrategy}
import beam.agentsim.agents.ridehail.{
  RideHailInquiry,
  RideHailManager,
  RideHailRequest,
  RideHailResponse,
  RideHailVehicleId
}
import beam.agentsim.agents.vehicles.AccessErrorCodes.RideHailNotRequestedError
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{BeamVehicle, _}
import beam.agentsim.events.resources.ReservationErrorCode
import beam.agentsim.events.{ModeChoiceEvent, ReplanningEvent, SpaceTime, TourModeChoiceEvent}
import beam.agentsim.infrastructure.{ParkingInquiry, ParkingInquiryResponse, ZonalParkingManager}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.router.BeamRouter.IntermodalUse._
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode._
import beam.router.TourModes.BeamTourMode
import beam.router.TourModes.BeamTourMode._
import beam.router.model.{BeamLeg, EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.skim.ActivitySimPathType.determineActivitySimPathTypesFromBeamMode
import beam.router.skim.{ActivitySimPathType, ActivitySimSkimmerFailedTripEvent}
import beam.router.skim.event.ODSkimmerFailedTripEvent
import beam.router.{Modes, RoutingWorker}
import beam.sim.population.AttributesOfIndividual
import beam.sim.{BeamServices, Geofence}
import beam.utils.logging.pattern.ask
import beam.utils.plan.sampling.AvailableModeUtils._
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.population.{Activity, Leg}
import org.matsim.core.population.routes.{NetworkRoute, RouteUtils}
import org.matsim.core.utils.misc.Time

import java.util.concurrent.atomic.AtomicReference
import scala.collection.JavaConverters
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
  * BEAM
  */
trait ChoosesMode {
  this: PersonAgent => // Self type restricts this trait to only mix into a PersonAgent

  private val BUFFER_PER_REPLANNING_ATTEMPT_IN_SEC: Int = 5

  private val dummyRHVehicle: StreetVehicle = createDummyVehicle(
    RideHailVehicleId.dummyVehicleId,
    beamServices.beamConfig.beam.agentsim.agents.rideHail.managers.head.initialization.procedural.vehicleTypeId,
    CAR,
    asDriver = false
  )

  private val rideHailTransitIntermodalUse: IntermodalUse =
    IntermodalUse.fromString(beamServices.beamConfig.beam.agentsim.agents.rideHailTransit.intermodalUse)

  //this dummy shared vehicles is used in R5 requests on egress side
  private val dummySharedVehicles: IndexedSeq[StreetVehicle] = possibleSharedVehicleTypes
    .map(_.vehicleCategory)
    .map {
      case VehicleCategory.Car =>
        createDummyVehicle(
          "dummySharedCar",
          beamServices.beamConfig.beam.agentsim.agents.vehicles.dummySharedCar.vehicleTypeId,
          CAR,
          asDriver = true
        )
      case VehicleCategory.Bike =>
        createDummyVehicle(
          "dummySharedBike",
          beamServices.beamConfig.beam.agentsim.agents.vehicles.dummySharedBike.vehicleTypeId,
          BIKE,
          asDriver = true
        )
      case category @ _ =>
        throw new IllegalArgumentException(
          s"Unsupported shared vehicle category $category. Only CAR | BIKE are supported."
        )
    }
    .toIndexedSeq

  private val rideHailModeToFleets: Map[ActivitySimPathType, List[String]] =
    this.beamServices.beamConfig.beam.agentsim.agents.rideHail.managers
      .flatMap(manager =>
        manager.supportedModes
          .split(',')
          .map(_.trim.toLowerCase)
          .flatMap(BeamMode.fromString)
          .filter(_.isRideHail)
          .flatMap(supportedBeamMode =>
            determineActivitySimPathTypesFromBeamMode(Some(supportedBeamMode), None)
              .map(_ -> manager.name)
          )
      )
      .groupBy(_._1)
      .map { case (mode, fleets) => mode -> fleets.map(_._2) }

  private def createDummyVehicle(id: String, vehicleTypeId: String, mode: BeamMode, asDriver: Boolean) =
    StreetVehicle(
      Id.create(id, classOf[BeamVehicle]),
      Id.create(
        vehicleTypeId,
        classOf[BeamVehicleType]
      ),
      SpaceTime(0.0, 0.0, 0),
      mode,
      asDriver = asDriver,
      needsToCalculateCost = true
    )

  private var sharedTeleportationVehiclesCount = 0

  private lazy val teleportationVehicleBeamType: BeamVehicleType = {
    val sharedVehicleType = beamScenario.vehicleTypes(
      Id.create(
        beamServices.beamConfig.beam.agentsim.agents.vehicles.dummySharedCar.vehicleTypeId,
        classOf[BeamVehicleType]
      )
    )

    sharedVehicleType
  }

  private def createSharedTeleportationVehicle(location: SpaceTime): BeamVehicle = {
    sharedTeleportationVehiclesCount += 1

    val stringId = s"${BeamVehicle.idPrefixSharedTeleportationVehicle}-$sharedTeleportationVehiclesCount"
    val vehicle = new BeamVehicle(
      BeamVehicle.createId(id, Some(stringId)),
      new Powertrain(0.0),
      beamVehicleType = teleportationVehicleBeamType,
      vehicleManagerId = new AtomicReference(VehicleManager.NoManager.managerId)
    )
    vehicle.spaceTime = location

    vehicle
  }

  def bodyVehiclePersonId: PersonIdWithActorRef = PersonIdWithActorRef(id, self)

  onTransition { case _ -> ChoosingMode =>
    val choosesModeData: ChoosesModeData = nextStateData.asInstanceOf[ChoosesModeData]
    val nextAct = nextActivity(choosesModeData.personData).get
    val currentTourStrategy = _experiencedBeamPlan.getTourStrategy[TourModeChoiceStrategy](nextAct)
    val currentTripMode = _experiencedBeamPlan.getTripStrategy[TripModeChoiceStrategy](nextAct).mode
    val currentTourMode = currentTourStrategy.tourMode
    val parentTourStrategy = getParentTourStrategy(choosesModeData.personData)

    (nextStateData, currentTripMode, currentTourMode) match {
      // If I am already on a tour in a vehicle, only that vehicle is available to me
      // Unless it's a walk based tour and I used that vehicle for egress on my first trip
      case (data: ChoosesModeData, _, tourMode @ Some(CAR_BASED | BIKE_BASED | FREIGHT_TOUR)) =>
        if (data.personData.currentTourPersonalVehicle.isDefined) {
          if (!beamVehicles.contains(data.personData.currentTourPersonalVehicle.get)) {
            logger.error(
              f"Something is broken in the current plan for agent ${this.id}. " +
              f"Tour vehicle ${data.personData.currentTourPersonalVehicle.get} doesn't exist. " +
              f"Problematic activity sequence ${_experiencedBeamPlan.activities.map(_.getType).toString()}. Re-requesting vehicles."
            )
            implicit val executionContext: ExecutionContext = context.system.dispatcher
            requestAvailableVehicles(
              fleetManagers,
              data.currentLocation,
              currentActivity(data.personData),
              tourMode match {
                case Some(CAR_BASED)  => Some(VehicleCategory.Car)
                case Some(BIKE_BASED) => Some(VehicleCategory.Bike)
                case _                => None
              }
            ) pipeTo self
          } else {
            val currentTourVehicle = Vector(beamVehicles(data.personData.currentTourPersonalVehicle.get))
            self ! MobilityStatusResponse(
              currentTourVehicle,
              getCurrentTriggerIdOrGenerate
            )
          }
        } else {
          implicit val executionContext: ExecutionContext = context.system.dispatcher
          requestAvailableVehicles(
            fleetManagers,
            data.currentLocation,
            currentActivity(data.personData),
            tourMode match {
              case Some(CAR_BASED)  => Some(VehicleCategory.Car)
              case Some(BIKE_BASED) => Some(VehicleCategory.Bike)
              case _                => None
            }
          ) pipeTo self
        }
      // If we're on a walk based tour but using a vehicle for access/egress
      case (data: ChoosesModeData, Some(BIKE_TRANSIT | DRIVE_TRANSIT), Some(WALK_BASED))
          if data.personData.currentTourPersonalVehicle.isDefined =>
        val currentTourPersonalVehicleId = data.personData.currentTourPersonalVehicle.get
        if (beamVehicles.contains(currentTourPersonalVehicleId)) {
          self ! MobilityStatusResponse(
            Vector(beamVehicles(currentTourPersonalVehicleId)),
            getCurrentTriggerIdOrGenerate
          )
        } else {
          logger.error(
            s"Person ${this.id} could not find vehicle $currentTourPersonalVehicleId. " +
            s"The cause is unknown. We will request an available vehicle from the vehicle manager."
          )
          implicit val executionContext: ExecutionContext = context.system.dispatcher
          requestAvailableVehicles(
            vehicleFleets,
            data.currentLocation,
            currentActivity(data.personData),
            Some(VehicleCategory.Car)
          ) pipeTo self
        }
      // Create teleportation vehicle if we are told to use teleportation
      case (data: ChoosesModeData, Some(HOV2_TELEPORTATION | HOV3_TELEPORTATION), _) =>
        val teleportationVehicle = createSharedTeleportationVehicle(data.currentLocation)
        val vehicles = Vector(ActualVehicle(teleportationVehicle))
        self ! MobilityStatusResponse(vehicles, getCurrentTriggerIdOrGenerate)
      // Only need to get available street vehicles if our mode requires such a vehicle
      case (data: ChoosesModeData, Some(CAR | CAR_HOV2 | CAR_HOV3 | DRIVE_TRANSIT), _) =>
        parentTourStrategy match {
          case Some(strategy)
              if strategy.tourMode.contains(CAR_BASED) && strategy.tourVehicle.exists(beamVehicles.contains) =>
            val currentTourVehicle = Vector(beamVehicles(strategy.tourVehicle.get))
            self ! MobilityStatusResponse(
              currentTourVehicle,
              getCurrentTriggerIdOrGenerate
            )
          case _ =>
            if (parentTourStrategy.exists(_.tourMode.contains(CAR_BASED))) {
              logError(s"Agent ${this.id} is on a car tour without an appropriate car. Generating an emergency one")
              if (parentTourStrategy.exists(_.tourVehicle.nonEmpty)) {
                logError(
                  s"Removing vehicle ${parentTourStrategy.get.tourVehicle.get} " +
                  s"from BeamVehicles for agent ${this.id}"
                )
                beamVehicles.remove(parentTourStrategy.get.tourVehicle.get)
              }
            }
            implicit val executionContext: ExecutionContext = context.system.dispatcher
            requestAvailableVehicles(
              vehicleFleets,
              data.currentLocation,
              currentActivity(data.personData),
              Some(VehicleCategory.Car)
            ) pipeTo self
        }
      case (data: ChoosesModeData, Some(BIKE | BIKE_TRANSIT), _) =>
        parentTourStrategy match {
          case Some(strategy)
              if strategy.tourMode.contains(BIKE_BASED) && strategy.tourVehicle.exists(beamVehicles.contains) =>
            val currentTourVehicle = Vector(beamVehicles(strategy.tourVehicle.get))
            self ! MobilityStatusResponse(
              currentTourVehicle,
              getCurrentTriggerIdOrGenerate
            )
          case _ =>
            if (parentTourStrategy.exists(_.tourMode.contains(BIKE_BASED))) {
              logError(s"Agent ${this.id} is on a bike based tour without a bike vehicle. Generating an emergency one")
            }
            implicit val executionContext: ExecutionContext = context.system.dispatcher
            requestAvailableVehicles(
              vehicleFleets,
              data.currentLocation,
              currentActivity(data.personData),
              Some(VehicleCategory.Bike)
            ) pipeTo self
        }
      // If we're on a walk based tour and have an egress vehicle defined we NEED to bring it home
      case (data: ChoosesModeData, None, Some(WALK_BASED))
          if currentTourStrategy.tourVehicle.isDefined && isLastTripWithinTour(nextAct) =>
        if (beamVehicles.contains(currentTourStrategy.tourVehicle.get)) {
          self ! MobilityStatusResponse(
            Vector(beamVehicles(currentTourStrategy.tourVehicle.get)),
            getCurrentTriggerIdOrGenerate
          )
        } else {
          logError(
            s"Missing tour strategy vehicle ${currentTourStrategy.tourVehicle.get} in beamVehicles for agent ${this.id}"
          )
          implicit val executionContext: ExecutionContext = context.system.dispatcher
          requestAvailableVehicles(
            vehicleFleets,
            data.currentLocation,
            currentActivity(data.personData),
            Some(VehicleCategory.Car)
          ) pipeTo self
        }

      // Finally, if we're starting from scratch, request all available vehicles
      case (data: ChoosesModeData, None, _) =>
        implicit val executionContext: ExecutionContext = context.system.dispatcher
        requestAvailableVehicles(
          vehicleFleets,
          data.currentLocation,
          currentActivity(data.personData)
        ) pipeTo self
      // Otherwise, send empty list to self
      case (
            _: ChoosesModeData,
            Some(CAV | RIDE_HAIL | RIDE_HAIL_POOLED | RIDE_HAIL_TRANSIT | WALK | WALK_TRANSIT),
            _
          ) =>
        self ! MobilityStatusResponse(Vector(), getCurrentTriggerIdOrGenerate)
      case (_, tripModeOption, tourModeOption) =>
        logger.error(
          s"Person ${this.id} has trip mode $tripModeOption and tour " +
          s"mode $tourModeOption, which shouldn't ever happen. Tick ${_currentTick.getOrElse(-1)}"
        )
        self ! MobilityStatusResponse(Vector(), getCurrentTriggerIdOrGenerate)
    }
  }

  /**
    * Sends a request to the given vehicle fleets to determine the availability of vehicles at a specific location and activity.
    * An optional vehicle category can be specified to filter the results.
    *
    * @param vehicleFleets                   the list of vehicle fleet actor references to query for available vehicles
    * @param location                        the location and time for which to check vehicle availability
    * @param activity                        the activity associated with the request, which may influence vehicle availability
    * @param requireVehicleCategoryAvailable an optional vehicle category to filter available vehicles; if specified, only vehicles of this category will be included
    * @return a Future containing a MobilityStatusResponse that includes a collection of available vehicles and a trigger ID
    */
  private def requestAvailableVehicles(
    vehicleFleets: Seq[ActorRef],
    location: SpaceTime,
    activity: Activity,
    requireVehicleCategoryAvailable: Option[VehicleCategory] = None
  ): Future[MobilityStatusResponse] = {
    implicit val executionContext: ExecutionContext = context.system.dispatcher
    Future
      .sequence(
        vehicleFleets.map(
          _ ? MobilityStatusInquiry(
            id,
            location,
            activity,
            requireVehicleCategoryAvailable,
            getCurrentTriggerIdOrGenerate
          )
        )
      )
      .map(listOfResponses =>
        MobilityStatusResponse(
          listOfResponses
            .collect { case MobilityStatusResponse(vehicles, _) =>
              vehicles
            }
            .flatten
            .toVector,
          getCurrentTriggerIdOrGenerate
        )
      )
  }

  when(ChoosingMode)(stateFunction = transform {
    case Event(MobilityStatusResponse(newlyAvailableBeamVehicles, triggerId), choosesModeData: ChoosesModeData) =>
      beamVehicles ++= newlyAvailableBeamVehicles.map(v => v.id -> v)

      val availableModes: Seq[BeamMode] = availableModesForPerson(matsimPlan.getPerson, choosesModeData.excludeModes)
      val personData = choosesModeData.personData
      val nextAct = nextActivity(personData).get

      // Note: This is usually duplicative of the tourModeChoiceStrategy in PersonData, but this handles some edge
      // cases around replanning and doesn't have concurrency issues
      val currentTourStrategy = _experiencedBeamPlan.getTourStrategy[TourModeChoiceStrategy](nextAct)
      val currentTripStrategy = _experiencedBeamPlan.getTripStrategy[TripModeChoiceStrategy](nextAct)
      val parentTourStrategy = getParentTourStrategy(personData)

      var currentTripMode = (currentTripStrategy.mode, personData.currentTripMode) match {
        case (None, None) => None
        case (Some(strategyMode), None) =>
          Some(strategyMode)
        case (Some(strategyMode), Some(dataMode)) if strategyMode == dataMode =>
          Some(strategyMode)
        case (None, Some(dataMode)) =>
          val updatedTripStrategy =
            TripModeChoiceStrategy(Some(dataMode))
          _experiencedBeamPlan.putStrategy(_experiencedBeamPlan.getTripContaining(nextAct), updatedTripStrategy)
          Some(dataMode)
        case (Some(DRIVE_TRANSIT), Some(WALK_TRANSIT)) if choosesModeData.isWithinTripReplanning =>
          logger.debug(
            "Keeping my _experiencedBeamPlan mode as DRIVE_TRANSIT and ChoosesModeData" +
            "as WALK_TRANSIT because I missed my initial transit leg but want to keep my vehicle"
          )
          Some(WALK_TRANSIT)
        case (Some(WALK_TRANSIT), Some(DRIVE_TRANSIT)) if choosesModeData.isWithinTripReplanning =>
          logger.warn(
            "Keeping my _experiencedBeamPlan mode as WALK_TRANSIT and ChoosesModeData" +
            s"as DRIVE_TRANSIT, even though I don't know why. Full personData: $personData "
          )
          Some(WALK_TRANSIT)
        case (Some(BIKE_TRANSIT), Some(WALK_TRANSIT)) if choosesModeData.isWithinTripReplanning =>
          logger.debug(
            "Keeping my _experiencedBeamPlan mode as BIKE_TRANSIT and ChoosesModeData" +
            "as WALK_TRANSIT because I missed my initial transit leg but want to keep my vehicle"
          )
          Some(WALK_TRANSIT)
        case _ =>
          log.error(
            s"Unexpected behavior: TripModeChoiceStrategy and personData have inconsistent states. " +
            s"TripModeChoiceStrategy mode = ${currentTripStrategy.mode}, " +
            s"personData currentTripMode = ${personData.currentTripMode}. " +
            s"isWithinTripReplanning: ${choosesModeData.isWithinTripReplanning}. " +
            s"Person ID: ${this.id}, Current Tick: ${_currentTick.getOrElse(-1)}, Full personData: $personData"
          )
          None
      }

      var availablePersonalStreetVehicles = {
        (currentTourStrategy.tourVehicle, currentTripMode) match {
          case (_, Some(HOV2_TELEPORTATION | HOV3_TELEPORTATION)) =>
            newlyAvailableBeamVehicles
          case (Some(vehId), _) =>
            newlyAvailableBeamVehicles.filter(_.id == vehId)
          case (_, None | Some(CAR | BIKE)) =>
            // In these cases, a personal vehicle will be involved, but filter out teleportation vehicles
            newlyAvailableBeamVehicles.filterNot(v => BeamVehicle.isSharedTeleportationVehicle(v.id))
          case (_, Some(DRIVE_TRANSIT | BIKE_TRANSIT)) =>
            if (isFirstOrLastTripWithinTour(nextAct)) {
              newlyAvailableBeamVehicles
            } else {
              Vector()
            }
          case _ =>
            Vector()
        }
      }

      val availableVehicleFromParentTour = (currentTourStrategy.tourMode, parentTourStrategy) match {
        // Can't use additional parent tour vehicles if i've already started on my subtour
        case (Some(_), _) => Vector()
        // Can't use vehicle from parent tour if it was used as access to transit
        case (None, Some(ps)) if ps.tourMode.contains(WALK_BASED) =>
          Vector()
        case _ =>
          personData.currentTourPersonalVehicle
            .flatMap(vehId => {
              beamVehicles.get(vehId) match {
                case Some(vehicle) => Some(vehicle)
                case None =>
                  logger.error(s"Vehicle with ID $vehId from currentTourPersonalVehicle not found in beamVehicles map")
                  //throw new NoSuchElementException(s"Vehicle ID $vehId not found")
                  None
              }
            })
            .toVector
      }
      availablePersonalStreetVehicles ++= availableVehicleFromParentTour

      val availableEmergencyVehicles =
        beamVehicles.filterKeys(k => k.toString.startsWith(f"${this.id.toString}-emergency")).values.toVector

      //      val otherNewAndTourVehicles =
      //        filterAvailableVehicles(availablePersonalStreetVehicles ++ availableEmergencyVehicles, currentTourStrategy)
      val otherNewAndTourVehicles = filterAvailableVehicles(
        availablePersonalStreetVehicles ++ availableEmergencyVehicles,
        currentTourStrategy,
        parentTourStrategy.nonEmpty
      ).distinct

      val availableModesGivenTourMode = getAvailableModesGivenTourMode(
        availableModes,
        otherNewAndTourVehicles,
        currentTourStrategy.tourMode,
        nextAct,
        Some(getCurrentTourStrategy(personData))
      )

      if (availableModesGivenTourMode.length == 1) {
        logger.debug(
          "Only one option (${availableModesGivenTourMode.head.value}) available so let's save some effort "
          + "and only query routes for that mode"
        )
        currentTripMode = availableModesGivenTourMode.headOption
      }

      val hasRideHail = availableModesGivenTourMode.contains(RIDE_HAIL)
      val (responsePlaceholders, requestId, remainingAvailableVehicles) = makeRoutingRequests(
        currentTripMode,
        currentTourStrategy.tourMode,
        hasRideHail,
        otherNewAndTourVehicles,
        choosesModeData,
        triggerId
      )

      val newPersonData = choosesModeData.copy(
        personData = personData
          .copy(
            currentTripMode = currentTripMode,
            currentTourMode = currentTourStrategy.tourMode
          ),
        routingResponse = responsePlaceholders.routingResponse,
        rideHailResult = responsePlaceholders.rideHailResult,
        rideHail2TransitRoutingResponse = responsePlaceholders.rideHail2TransitRoutingResponse,
        rideHail2TransitRoutingRequestId = requestId,
        rideHail2TransitAccessResult = responsePlaceholders.rideHail2TransitAccessResult,
        rideHail2TransitEgressResult = responsePlaceholders.rideHail2TransitEgressResult,
        availablePersonalStreetVehicles = otherNewAndTourVehicles,
        allAvailableStreetVehicles = remainingAvailableVehicles,
        cavTripLegs = responsePlaceholders.cavTripLegs,
        routingFinished = choosesModeData.routingFinished
          || responsePlaceholders.routingResponse == RoutingResponse.dummyRoutingResponse
      )
      stay() using newPersonData
    /*
     * Receive and store data needed for choice.
     */
    case Event(
          theRouterResult @ RoutingResponse(_, requestId, _, _, _, _, _),
          choosesModeData: ChoosesModeData
        ) if choosesModeData.routingRequestToLegMap.contains(requestId) =>
      //handling router responses for shared vehicles
      val routingResponse = choosesModeData.routingResponse.get
      val tripIdentifier = choosesModeData.routingRequestToLegMap(requestId)
      val newMap = choosesModeData.routingRequestToLegMap - requestId
      val routingFinished = newMap.isEmpty
      val mayBeTripIdx: Option[Int] = routingResponse.itineraries.zipWithIndex.collectFirst {
        case (trip, i) if tripIdentifier.isAppropriateTrip(trip) => i
      }
      val maybeNewChoosesModeData =
        for {
          tripIdx <- mayBeTripIdx
          trip = routingResponse.itineraries(tripIdx)
          tripWithVehicle = theRouterResult.itineraries.find(_.legs.size == 3)
          newTrips: Seq[EmbodiedBeamTrip] =
            if (tripWithVehicle.isEmpty) {
              //need to delete this trip: not found the right way to the shared vehicle or destination
              routingResponse.itineraries.patch(tripIdx, Nil, 0)
            } else {
              //drop everything after the last transit and add the new legs on the shared vehicle
              val appendedEgressLegs = trip.legs.reverse
                .dropWhile(!_.beamLeg.mode.isTransit)
                .reverse ++ tripWithVehicle.get.legs
              val appendTrip = trip.copy(legs = appendedEgressLegs)
              routingResponse.itineraries.patch(tripIdx, Seq(appendTrip), 1)
            }
          rr = routingResponse.copy(itineraries = newTrips)
        } yield choosesModeData.copy(
          routingResponse = Some(rr),
          routingFinished = routingFinished,
          routingRequestToLegMap = newMap
        )
      val newChoosesModeData = maybeNewChoosesModeData.getOrElse(choosesModeData)

      stay() using newChoosesModeData
        .copy(
          routingResponse =
            if (routingFinished) Some(correctRoutingResponse(newChoosesModeData.routingResponse.get))
            else newChoosesModeData.routingResponse,
          routingFinished = routingFinished,
          routingRequestToLegMap = newMap
        )

    case Event(
          theRouterResult @ RoutingResponse(_, requestId, _, _, _, _, _),
          choosesModeData: ChoosesModeData
        ) if choosesModeData.rideHail2TransitRoutingRequestId.contains(requestId) =>
      theRouterResult.itineraries.view.foreach { resp =>
        resp.beamLegs.filter(_.mode == CAR).foreach { leg =>
          routeHistory.rememberRoute(leg.travelPath.linkIds, leg.startTime)
        }
      }
      // HACKY FIX: The old behavior just chose the first RH_TRANSIT itinerary from all the possible RH_TRANSIT
      // itineraries produced by R5. But different itineraries may have different combinations of walk/rh for
      // access/egress, and the egress RH legs in different itineraries may have different starting locations. So we'd
      // really need to send separate RH requests for each RH_TRANSIT itinerary if we wanted to do it correctly. This
      // sounds too complicated and costly, so instead we just run a route choice on the RH_TRANSIT itineraries returned
      // by R5. These don't necessarily have correct wait times, but R5 has been updated to give them appropriate costs.
      // Once we've chosen the best itinerary we can send requests to the RHM to fill in true costs and wait times

      //      val rhTransitTrip = modeChoiceCalculator(
      //        theRouterResult.itineraries.filter(_.tripClassifier == RIDE_HAIL_TRANSIT).toIndexedSeq,
      //        matsimPlan.getPerson.getCustomAttributes
      //          .get("beam-attributes")
      //          .asInstanceOf[AttributesOfIndividual],
      //        nextActivity(choosesModeData.personData),
      //        Some(currentActivity(choosesModeData.personData)),
      //        Some(matsimPlan.getPerson)
      //      )

      val rhTransitTrip = theRouterResult.itineraries
        .filter(trip =>
          (trip.tripClassifier == RIDE_HAIL_TRANSIT) && (trip.legs.head.beamLeg.startTime > (_currentTick.get + 300))
        ) match {
        case Seq() => None
        case x     => Some(x.minBy(_.totalTravelTimeInSecs))
      }

      // If there's a drive-transit trip AND we don't have an error RH2Tr response (due to no desire to use RH) then seek RH on access and egress
      val newPersonData =
        if (
          shouldAttemptRideHail2Transit(
            rhTransitTrip,
            choosesModeData.rideHail2TransitAccessResult
          )
        ) {
          val accessSegment =
            rhTransitTrip.get.legs
              .takeWhile(!_.beamLeg.mode.isMassTransit)
              .map(_.beamLeg)
          val egressSegment =
            rhTransitTrip.get.legs.reverse.takeWhile(!_.beamLeg.mode.isTransit).reverse.map(_.beamLeg)
          val (accessId, accessResult) =
            if (
              (accessSegment.map(_.travelPath.distanceInM).sum > 0) & accessSegment
                .exists(l => l.mode.isRideHail | l.mode == CAR)
            ) {
              (makeRideHailRequestFromBeamLeg(accessSegment), None)
            } else {
              (None, Some(RideHailResponse.dummyWithError(RideHailNotRequestedError)))
            }
          val (egressId, egressResult) =
            if (
              (egressSegment.map(_.travelPath.distanceInM).sum > 0) & egressSegment
                .exists(l => l.mode.isRideHail | l.mode == CAR)
            ) {
              (makeRideHailRequestFromBeamLeg(egressSegment.toVector), None)
            } else {
              (None, Some(RideHailResponse.dummyWithError(RideHailNotRequestedError)))
            }
          choosesModeData.copy(
            rideHail2TransitRoutingResponse = Some(rhTransitTrip.get),
            rideHail2TransitAccessResult = if (accessId.isEmpty) {
              Some(RideHailResponse.dummyWithError(RideHailNotRequestedError))
            } else {
              None
            },
            rideHail2TransitAccessInquiryId = accessId,
            rideHail2TransitEgressResult = if (egressId.isEmpty) {
              Some(RideHailResponse.dummyWithError(RideHailNotRequestedError))
            } else {
              None
            },
            rideHail2TransitEgressInquiryId = egressId
          )
        } else {
          choosesModeData.copy(
            rideHail2TransitRoutingResponse = Some(EmbodiedBeamTrip.empty),
            rideHail2TransitAccessResult = Some(RideHailResponse.dummyWithError(RideHailNotRequestedError)),
            rideHail2TransitEgressResult = Some(RideHailResponse.dummyWithError(RideHailNotRequestedError)),
            routingFinished =
              choosesModeData.routingFinished || (choosesModeData.rideHailResult.nonEmpty && choosesModeData.routingResponse.nonEmpty)
          )
        }
      stay() using newPersonData

    case Event(response: RoutingResponse, choosesModeData: ChoosesModeData) =>
      response.itineraries.view.foreach { resp =>
        resp.beamLegs.filter(_.mode == CAR).foreach { leg =>
          routeHistory.rememberRoute(leg.travelPath.linkIds, leg.startTime)
        }
      }
      val thereAreTeleportationItineraries = response.itineraries.foldLeft(false) { (thereAreTeleportations, trip) =>
        val thereAreTeleportationVehicles = trip.legs.foldLeft(false) { (accum, leg) =>
          accum || BeamVehicle.isSharedTeleportationVehicle(leg.beamVehicleId)
        }
        thereAreTeleportations || thereAreTeleportationVehicles
      }
      val newParkingRequestIds = if (thereAreTeleportationItineraries) {
        choosesModeData.parkingRequestIds
      } else {
        val parkingRequestIds: Seq[(Int, VehicleOnTrip)] = makeParkingInquiries(choosesModeData, response.itineraries)
        choosesModeData.parkingRequestIds ++ parkingRequestIds
      }
      val currentMode = choosesModeData.personData.currentTripMode
      val updatedResponse =
        if (
          currentMode.exists(_.isTeleportation) & !response.itineraries
            .exists(_.tripClassifier.isTeleportation)
        ) {
          logger.warn(
            s"Agent ${this.id} is on a " +
            s"${response.request.map(r => geo.distUTMInMeters(r.originUTM, r.destinationUTM) / 1609.3).getOrElse(-1.0)}" +
            " mile teleportation trip without a route. Creating a default one."
          )
          Some(
            response.copy(itineraries =
              response.itineraries :+ RoutingWorker.createBushwackingTrip(
                response.request.get.originUTM,
                response.request.get.destinationUTM,
                response.request.get.departureTime,
                response.request.get.streetVehicles.find(v => BeamVehicle.isSharedTeleportationVehicle(v.id)) match {
                  case Some(veh) => veh
                  case _ =>
                    logger.warn(
                      s"Agent ${this.id} is on a teleportation trip without a vehicle. Creating a new one." +
                      s" Problematic response: $response"
                    )
                    createSharedTeleportationVehicle(choosesModeData.currentLocation).toStreetVehicle
                      .copy(mode = currentMode match {
                        case Some(HOV2_TELEPORTATION) => CAR_HOV2
                        case Some(HOV3_TELEPORTATION) => CAR_HOV3
                        case _                        => CAR
                      })
                },
                geo,
                choosesModeData.personData.currentTripMode.get
              )
            )
          )
        } else None

      val dummyVehiclesPresented = makeVehicleRequestsForDummySharedVehicles(response.itineraries)
      val newData = if (dummyVehiclesPresented) {
        choosesModeData.copy(routingResponse = Some(response), parkingRequestIds = newParkingRequestIds)
      } else {
        choosesModeData.copy(
          routingResponse = Some(correctRoutingResponse(updatedResponse.getOrElse(response))),
          parkingRequestIds = newParkingRequestIds,
          routingFinished =
            choosesModeData.rideHail2TransitRoutingResponse.nonEmpty || choosesModeData.rideHail2TransitRoutingRequestId.isEmpty // CHANGE APRIL 2024: choice was moving ahead before RH transit result completed
        )
      }

      // If person plan doesn't have a route for an activity create and save it
      for {
        activity <- nextActivity(choosesModeData.personData)
        leg      <- _experiencedBeamPlan.getTripContaining(activity).leg if leg.getRoute == null
      } {
        val links =
          response.itineraries
            .flatMap(_.beamLegs)
            .find(_.mode == BeamMode.CAR)
            .map { beamLeg =>
              beamLeg.travelPath.linkIds
                .map(id => Id.create(id, classOf[Link]))
                .toList
            }
            .getOrElse(List.empty)

        if (links.nonEmpty) {
          val route = RouteUtils.createNetworkRoute(JavaConverters.seqAsJavaList(links), beamScenario.network)
          leg.setRoute(route)
        }
      }

      stay() using newData

    case Event(theRideHailResult: RideHailResponse, choosesModeData: ChoosesModeData) =>
      val newPersonData = Some(theRideHailResult.request.requestId) match {
        case choosesModeData.rideHail2TransitAccessInquiryId =>
          choosesModeData.copy(
            rideHail2TransitAccessResult = Some(theRideHailResult),
            routingFinished = choosesModeData.rideHail2TransitEgressResult.nonEmpty
          )
        case choosesModeData.rideHail2TransitEgressInquiryId =>
          choosesModeData.copy(
            rideHail2TransitEgressResult = Some(theRideHailResult),
            routingFinished = choosesModeData.rideHail2TransitAccessResult.nonEmpty
          )
        case _ =>
          val routingFinished =
            choosesModeData.rideHail2TransitRoutingResponse.nonEmpty &&
            choosesModeData.routingResponse.nonEmpty &&
            choosesModeData.rideHail2TransitAccessResult.nonEmpty &&
            choosesModeData.rideHail2TransitEgressResult.nonEmpty
          choosesModeData.copy(
            rideHailResult = Some(theRideHailResult),
            routingFinished = choosesModeData.routingFinished || routingFinished
          )
      }
      stay() using newPersonData
    case Event(parkingInquiryResponse: ParkingInquiryResponse, choosesModeData: ChoosesModeData) =>
      val newPersonData = choosesModeData.copy(parkingResponses =
        choosesModeData.parkingResponses +
        (choosesModeData.parkingRequestIds(parkingInquiryResponse.requestId) -> parkingInquiryResponse)
      )
      stay using newPersonData
    case Event(_: RetryModeChoice, choosesModeData: ChoosesModeData) =>
      val newPersonData = choosesModeData.copy(
        routingFinished = true,
        parkingRequestIds = Map.empty // Clear pending parking requests
      )
      stay using newPersonData
    case Event(cavTripLegsResponse: CavTripLegsResponse, choosesModeData: ChoosesModeData) =>
      stay using choosesModeData.copy(cavTripLegs = Some(cavTripLegsResponse))
    //handling response with the shared vehicle nearby the egress legs
    case Event(mobStatuses: MobilityStatusWithLegs, choosesModeData: ChoosesModeData) =>
      val mobilityStatuses = mobStatuses.responses.map { case (trip, leg, response) =>
        (trip, leg, response.streetVehicle.collect { case token: Token => token })
      }
      val tripsToDelete = mobilityStatuses.collect { case (trip, _, tokens) if tokens.isEmpty => trip }.toSet
      val tripsToModify = mobilityStatuses.collect { case (trip, _, tokens) if tokens.nonEmpty => trip }.toSet
      val legMap = mobilityStatuses
        .filter { case (trip, _, _) => tripsToModify.contains(trip) }
        .map { case (_, leg, response) => leg -> response }
        .toMap

      val rr = choosesModeData.routingResponse.get
      val newTrips = rr.itineraries
        .filterNot(tripsToDelete.contains)
        .map {
          case trip if tripsToModify.contains(trip) =>
            //find nearest provided vehicle for each leg
            val legVehicles: Map[EmbodiedBeamLeg, Token] = trip.legs.collect {
              case leg if legMap.contains(leg) =>
                val nearestVehicle = legMap(leg)
                  .minBy(token =>
                    geo.distUTMInMeters(
                      geo.wgs2Utm(leg.beamLeg.travelPath.startPoint.loc),
                      token.streetVehicle.locationUTM.loc
                    )
                  )
                leg -> nearestVehicle
            }.toMap
            //replace the dummy vehicle with the provided token for each leg
            val newLegs = trip.legs.map {
              case leg if legVehicles.contains(leg) =>
                val token = legVehicles(leg)
                leg.copy(beamVehicleId = token.id)
              case leg => leg
            }
            beamVehicles ++= legVehicles.values.map(token => token.id -> token)
            trip.copy(legs = newLegs)
          case trip => trip
        }
      //issue routing request for egress legs:
      // final transit stop -> destination
      val routingRequestMap = generateRoutingRequestsForEgress(newTrips)
      routingRequestMap.keys.foreach(routingRequest => router ! routingRequest)
      val newRoutingResponse = rr.copy(itineraries = newTrips)
      //issue parking request for the shared vehicle
      val parkingRequestIds = makeParkingInquiries(choosesModeData, newTrips)
      //correct routing response if routing is finished (no appropriate vehicles available)
      stay using choosesModeData
        .copy(
          routingResponse =
            Some(if (routingRequestMap.isEmpty) correctRoutingResponse(newRoutingResponse) else newRoutingResponse),
          parkingRequestIds = choosesModeData.parkingRequestIds ++ parkingRequestIds,
          routingFinished = routingRequestMap.isEmpty,
          routingRequestToLegMap = routingRequestMap.map { case (request, tripMode) =>
            request.requestId -> tripMode
          }
        )
  } using completeChoiceIfReady)

  /**
    * Generates a sequence of parking inquiries for vehicles in the given itineraries, based on the
    * chosen mode data and parking behavior of the vehicles. It checks which vehicles have already
    * been requested for parking and creates inquiries for the remaining vehicles.
    *
    * @param choosesModeData Data related to the mode choice of the person, including parking
    */
  private def makeParkingInquiries(
    choosesModeData: ChoosesModeData,
    itineraries: Seq[EmbodiedBeamTrip]
  ): Seq[(Int, VehicleOnTrip)] = {

    val parkingLegs: Seq[(TripIdentifier, EmbodiedBeamLeg)] = itineraries
      .flatMap { trip =>
        trip.legs
          .filter(leg => legVehicleHasParkingBehavior(leg) && !isLegOnDummySharedVehicle(leg))
          .map(TripIdentifier(trip) -> _)
      }

    val alreadyRequested = choosesModeData.parkingRequestIds.map { case (_, vehicleOnTrip) => vehicleOnTrip }.toSet

    val nextAct = nextActivity(choosesModeData.personData).get
    val (_, parkingInquiries) =
      parkingLegs.foldLeft((alreadyRequested, Seq.empty[(VehicleOnTrip, ParkingInquiry)])) {
        case ((requested, seq), (tripIdentifier, leg)) =>
          val vehicleOnTrip = VehicleOnTrip(leg.beamVehicleId, tripIdentifier)
          if (requested.contains(vehicleOnTrip)) {
            (requested, seq)
          } else {
            val veh = beamVehicles(leg.beamVehicleId).vehicle
            (
              requested + vehicleOnTrip,
              seq :+ (vehicleOnTrip -> ParkingInquiry.init(
                SpaceTime(geo.wgs2Utm(leg.beamLeg.travelPath.endPoint.loc), leg.beamLeg.endTime),
                nextAct.getType,
                VehicleManager.getReservedFor(veh.vehicleManagerId.get).get,
                Some(veh),
                None,
                Some(this.id),
                attributes.valueOfTime,
                getActivityEndTime(nextAct, beamServices) - leg.beamLeg.endTime,
                reserveStall = false,
                triggerId = getCurrentTriggerIdOrGenerate
              ))
            )
          }
      }

    parkingInquiries.map { case (vehicleOnTrip, inquiry) =>
      park(inquiry)
      inquiry.requestId -> vehicleOnTrip
    }
  }

  private def generateRoutingRequestsForEgress(
    newTrips: Seq[EmbodiedBeamTrip]
  ): Map[RoutingRequest, TripIdentifier] = {

    //we saving in the map (routing request for egress part -> trip identifier)
    newTrips.foldLeft(Map.empty[RoutingRequest, TripIdentifier]) { case (tripMap, trip) =>
      val transitAndDriveLeg: Option[(EmbodiedBeamLeg, EmbodiedBeamLeg)] = trip.legs.zip(trip.legs.tail).find {
        case (leg, nextLeg) if leg.beamLeg.mode.isTransit && isDriveVehicleLeg(nextLeg) =>
          val vehicleLocation = beamVehicles(nextLeg.beamVehicleId).streetVehicle.locationUTM.loc
          val walkDistance = geo.distUTMInMeters(geo.wgs2Utm(leg.beamLeg.travelPath.endPoint.loc), vehicleLocation)
          walkDistance > beamServices.beamConfig.beam.agentsim.thresholdForWalkingInMeters
        case _ => false
      }
      transitAndDriveLeg match {
        case Some((transitLeg, sharedVehicleLeg)) =>
          //the router should return a walk leg to the vehicle, vehicle leg and a walk leg to the destination
          val bodyLocationAfterTransit = geo.wgs2Utm(transitLeg.beamLeg.travelPath.endPoint)
          val bodyVehicle = createBodyStreetVehicle(bodyLocationAfterTransit)
          val finalDestination = geo.wgs2Utm(trip.legs.last.beamLeg.travelPath.endPoint.loc)
          val egressRequest = RoutingRequest(
            bodyLocationAfterTransit.loc,
            finalDestination,
            bodyLocationAfterTransit.time,
            withTransit = false,
            Some(id),
            IndexedSeq(bodyVehicle, beamVehicles(sharedVehicleLeg.beamVehicleId).streetVehicle),
            Some(attributes),
            triggerId = getCurrentTriggerIdOrGenerate
          )
          tripMap + (egressRequest -> TripIdentifier(trip))
        case None =>
          tripMap
      }
    }
  }

  private def createBodyStreetVehicle(locationUTM: SpaceTime): StreetVehicle = {
    StreetVehicle(
      body.id,
      body.beamVehicleType.id,
      locationUTM,
      WALK,
      asDriver = true,
      needsToCalculateCost = false
    )
  }

  private def correctRoutingResponse(response: RoutingResponse) = {
    val theRouterResult = response.copy(itineraries = response.itineraries.map { it =>
      it.copy(
        it.legs.flatMap(embodiedLeg =>
          if (legVehicleHasParkingBehavior(embodiedLeg))
            EmbodiedBeamLeg.splitLegForParking(embodiedLeg, beamServices, transportNetwork)
          else Vector(embodiedLeg)
        )
      )
    })
    val correctedItins = theRouterResult.itineraries
      .map { trip =>
        if (trip.legs.head.beamLeg.mode != WALK) {
          val startLeg =
            dummyWalkLeg(
              trip.legs.head.beamLeg.startTime,
              trip.legs.head.beamLeg.travelPath.startPoint.loc,
              unbecomeDriverOnCompletion = false
            )
          trip.copy(legs = startLeg +: trip.legs)
        } else trip
      }
      .map { trip =>
        if (trip.legs.last.beamLeg.mode != WALK) {
          val endLeg =
            dummyWalkLeg(
              trip.legs.last.beamLeg.endTime,
              trip.legs.last.beamLeg.travelPath.endPoint.loc,
              unbecomeDriverOnCompletion = true
            )
          trip.copy(legs = trip.legs :+ endLeg)
        } else trip
      }
    val responseCopy = theRouterResult.copy(itineraries = correctedItins)
    responseCopy
  }

  private def legVehicleHasParkingBehavior(embodiedLeg: EmbodiedBeamLeg): Boolean = {
    /* we need to park cars and any shared vehicles */
    /* teleportation vehicles are not actual vehicles, so, they do not require parking */
    val isTeleportationVehicle = BeamVehicle.isSharedTeleportationVehicle(embodiedLeg.beamVehicleId)
    val isRealCar = embodiedLeg.beamLeg.mode == CAR && dummyRHVehicle.id != embodiedLeg.beamVehicleId
    !isTeleportationVehicle && (
      isRealCar
      || (embodiedLeg.beamLeg.mode == BIKE && beamVehicles.get(embodiedLeg.beamVehicleId).forall(_.isInstanceOf[Token]))
    )
  }

  private def dummyWalkLeg(time: Int, location: Location, unbecomeDriverOnCompletion: Boolean) = {
    EmbodiedBeamLeg(
      BeamLeg.dummyLeg(time, location),
      body.id,
      body.beamVehicleType.id,
      asDriver = true,
      0,
      unbecomeDriverOnCompletion = unbecomeDriverOnCompletion
    )
  }

  private def isDriveVehicleLeg(leg: EmbodiedBeamLeg) = {
    leg.asDriver && leg.beamLeg.mode != BeamMode.WALK
  }

  private def shouldAttemptRideHail2Transit(
    driveTransitTrip: Option[EmbodiedBeamTrip],
    rideHail2TransitResult: Option[RideHailResponse]
  ): Boolean = {
    driveTransitTrip.isDefined && driveTransitTrip.get.legs
      .exists(leg => beamScenario.rideHailTransitModes.contains(leg.beamLeg.mode)) &&
    rideHail2TransitResult.getOrElse(RideHailResponse.DUMMY).error.isEmpty // NOTE: will this ever be nonempty?
  }

  private def makeRideHailRequestFromBeamLeg(legs: Seq[BeamLeg]): Option[Int] = {
    val inquiry = RideHailRequest(
      RideHailInquiry,
      bodyVehiclePersonId,
      beamServices.geo.wgs2Utm(legs.head.travelPath.startPoint.loc),
      legs.head.startTime,
      beamServices.geo.wgs2Utm(legs.last.travelPath.endPoint.loc),
      asPooled = true,
      withWheelchair = wheelchairUser,
      requestTime = _currentTick.get,
      requester = self,
      rideHailServiceSubscription = attributes.rideHailServiceSubscription,
      triggerId = getCurrentTriggerIdOrGenerate
    )
    rideHailManager ! inquiry
    Some(inquiry.requestId)
  }

  private def makeVehicleRequestsForDummySharedVehicles(trips: Seq[EmbodiedBeamTrip]): Boolean = {
    implicit val executionContext: ExecutionContext = context.system.dispatcher
    //get all the shared vehicles to request tokens for them
    val tripLegPairs = trips.flatMap(trip =>
      trip.legs
        .filter(legs => isLegOnDummySharedVehicle(legs))
        .map(leg => (trip, leg))
    )
    if (tripLegPairs.nonEmpty) {
      Future
        .sequence(
          tripLegPairs.collect { case (trip, leg) =>
            requestAvailableVehicles(sharedVehicleFleets, geo.wgs2Utm(leg.beamLeg.travelPath.startPoint), null)
              .map((trip, leg, _))
          }
        )
        .map { responses: Seq[(EmbodiedBeamTrip, EmbodiedBeamLeg, MobilityStatusResponse)] =>
          MobilityStatusWithLegs(responses)
        } pipeTo self
      true
    } else {
      false
    }
  }

  private def isLegOnDummySharedVehicle(beamLeg: EmbodiedBeamLeg): Boolean = {
    isDummySharedVehicle(beamLeg.beamVehicleId)
  }

  private def isDummySharedVehicle(beamVehicleId: Id[BeamVehicle]): Boolean =
    dummySharedVehicles.exists(_.id == beamVehicleId)

  case object FinishingModeChoice extends BeamAgentState

  /**
    * Creates a sequence of ride-hail to transit itineraries based on the provided results for
    * ride-hail access, ride-hail egress, and a drive transit trip.
    *
    * @param rideHail2TransitAccessResult the result of the ride-hail-to-transit access leg request,
    *                                     containing ride-hail vehicle options and associated data
    * @param rideHail2TransitEgressResult the result of the ride-hail-to-transit egress leg request,
    *                                     containing ride-hail vehicle options and associated data
    * @param driveTransitTrip             the returned drive transit trip that will be turned into a
    *                                     ridehail transit trip
    * @return a vector of possible ride-hail to transit itineraries as embodied trips; returns an empty
    *         vector if an itinerary cannot be generated
    */
  private def createRideHail2TransitItin(
    rideHail2TransitAccessResult: RideHailResponse,
    rideHail2TransitEgressResult: RideHailResponse,
    driveTransitTrip: EmbodiedBeamTrip
  ): Vector[EmbodiedBeamTrip] = {
    if (!driveTransitTrip.tripClassifier.equals(RIDE_HAIL_TRANSIT)) { Vector.empty[EmbodiedBeamTrip] }
    else if (
      rideHail2TransitAccessResult.error.forall(error => error == RideHailNotRequestedError) &&
      rideHail2TransitEgressResult.error.forall(error => error == RideHailNotRequestedError)
    ) {
      val (accessLegs, timeToCustomer) = rideHail2TransitAccessResult.error match {
        case Some(RideHailNotRequestedError) =>
          (Vector(Vector(driveTransitTrip.legs.head)), 0)
        case _ =>
          val timeToCustomer = rideHail2TransitAccessResult.travelProposal.get.passengerSchedule
            .legsBeforePassengerBoards(bodyVehiclePersonId)
            .map(_.duration)
            .sum
          val legs = travelProposalToRideHailLegs(
            rideHail2TransitAccessResult.travelProposal.get,
            rideHail2TransitAccessResult.rideHailManagerName,
            None
          )
          (legs, timeToCustomer)
      }
      val egressLegs = rideHail2TransitEgressResult.error match {
        case Some(RideHailNotRequestedError) =>
          Vector(Vector(driveTransitTrip.legs.last))
        case _ =>
          travelProposalToRideHailLegs(
            rideHail2TransitEgressResult.travelProposal.get,
            rideHail2TransitEgressResult.rideHailManagerName,
            None
          )
      }

      for {
        accessLeg     <- accessLegs
        egressLeg     <- egressLegs
        rhTransitTrip <- createRideHailTransitTrip(driveTransitTrip, accessLeg, timeToCustomer, egressLeg)
      } yield rhTransitTrip
    } else Vector.empty[EmbodiedBeamTrip]
  }

  /**
    * Creates a ride-hail transit trip by combining a drive-transit trip, ride-hail access legs,
    * and ride-hail egress legs, while adjusting for timing constraints and extra wait times.
    *
    * @param driveTransitTrip the original drive-transit trip composed of a sequence of legs
    * @param tncAccessLeg     the sequence of ride-hail access legs used to reach the transit
    * @param timeToCustomer   time required for the ride-hail vehicle to reach the customer
    * @param tncEgressLeg     the sequence of ride-hail egress legs used after the transit
    * @return an optional ride-hail transit trip combining the input components if timing constraints are satisfied,
    *         or None if the trip cannot be created due to excessive wait time
    */
  private def createRideHailTransitTrip(
    driveTransitTrip: EmbodiedBeamTrip,
    tncAccessLeg: Vector[EmbodiedBeamLeg],
    timeToCustomer: Int,
    tncEgressLeg: Vector[EmbodiedBeamLeg]
  ): Option[EmbodiedBeamTrip] = {
    val transitLegs = driveTransitTrip.legs.view
      .dropWhile(leg => !leg.beamLeg.mode.isTransit)
      .reverse
      .dropWhile(leg => !leg.beamLeg.mode.isTransit)
      .reverse
    val (extraWaitTimeBuffer, accessLegAdjustment) = tncAccessLeg.filter(_.isRideHail) match {
      case Vector() =>
        (Int.MaxValue, 0)
      case rhLegs =>
        val latenessToFirstTransitLeg = tncAccessLeg.last.beamLeg.endTime - transitLegs.head.beamLeg.startTime max 0
        val startTimeBufferForWaiting =
          300.0 + timeToCustomer.toDouble * 0.25 + latenessToFirstTransitLeg.toDouble
        val extraWaitTimeBuffer = rhLegs.last.beamLeg.endTime -
          tncAccessLeg.map(_.beamLeg.duration).sum - timeToCustomer - _currentTick.get - startTimeBufferForWaiting
        (extraWaitTimeBuffer.floor.toInt, startTimeBufferForWaiting.floor.toInt)
    }

    if (extraWaitTimeBuffer > 0) {
      Some(
        surroundWithWalkLegsIfNeededAndMakeTrip(
          Vector(
            tncAccessLeg.head.copy(beamLeg =
              tncAccessLeg.head.beamLeg.updateStartTime(tncAccessLeg.head.beamLeg.startTime - accessLegAdjustment)
            )
          ) ++ tncAccessLeg.tail ++ transitLegs ++ tncEgressLeg
        )
      )
    } else None
  }

  private def addParkingCostToItins(
    itineraries: Seq[EmbodiedBeamTrip],
    parkingResponses: Map[VehicleOnTrip, ParkingInquiryResponse]
  ): Seq[EmbodiedBeamTrip] = {
    itineraries.map { itin =>
      itin.tripClassifier match {
        case mode if Modes.isPersonalVehicleMode(mode) =>
          // find parking legs (the subsequent leg of the same vehicle)
          val parkingLegs = itin.legs.zip(itin.legs.tail).collect {
            case (leg1, leg2) if leg1.beamVehicleId == leg2.beamVehicleId && legVehicleHasParkingBehavior(leg2) => leg2
          }
          val walkLegsAfterParkingWithParkingResponses = itin.legs
            .zip(itin.legs.tail)
            .collect {
              case (leg1, leg2) if parkingLegs.contains(leg1) && leg2.beamLeg.mode == BeamMode.WALK =>
                leg2 -> parkingResponses(VehicleOnTrip(leg1.beamVehicleId, TripIdentifier(itin)))
            }
            .toMap
          val newLegs = itin.legs.map { leg =>
            if (parkingLegs.contains(leg)) {
              if (leg.beamLeg.duration < 0) { logger.error("Negative parking leg duration {}", leg) }
              leg.copy(
                cost = leg.cost + parkingResponses(
                  VehicleOnTrip(leg.beamVehicleId, TripIdentifier(itin))
                ).stall.costInDollars
              )
            } else if (walkLegsAfterParkingWithParkingResponses.contains(leg)) {
              if (leg.beamLeg.duration < 0) { logger.error("Negative walk after parking leg duration {}", leg) }
              val dist = geo.distUTMInMeters(
                geo.wgs2Utm(leg.beamLeg.travelPath.endPoint.loc),
                walkLegsAfterParkingWithParkingResponses(leg).stall.locationUTM
              )
              val travelTime: Int = (dist / ZonalParkingManager.AveragePersonWalkingSpeed).toInt
              leg.copy(beamLeg = leg.beamLeg.scaleToNewDuration(travelTime))
            } else {
              if (leg.beamLeg.duration < 0) { logger.error("Negative non-parking leg duration {}", leg) }
              leg
            }
          }
          itin.copy(legs = newLegs)
        case _ =>
          itin
      }
    }
  }

  /**
    * Filters the available vehicles based on the current tour strategy.
    * Only includes vehicles that align with the conditions defined by the method logic.
    *
    * @param allAvailableStreetVehicles A vector containing all street vehicles currently available.
    * @param currentTourStrategy        The strategy object representing the current tour mode and vehicle preferences.
    * @return A vector of filtered vehicles or tokens meeting the specified conditions.
    */
  private def filterAvailableVehicles(
    allAvailableStreetVehicles: Vector[VehicleOrToken],
    currentTourStrategy: TourModeChoiceStrategy,
    onSubTour: Boolean = false
  ): Vector[VehicleOrToken] = {
    val tourVehicle = currentTourStrategy.tourVehicle
    val tourMode = currentTourStrategy.tourMode
    val newAndTourVehicles = allAvailableStreetVehicles ++ currentTourStrategy.tourVehicle
      .flatMap(v => beamVehicles.get(v))
      .filterNot(v => v.vehicle.isSharedVehicle && !BeamVehicle.isEmergencyVehicle(v.id))
      .toVector
      .distinct

    newAndTourVehicles.flatMap {
      case ActualVehicle(beamVehicle) if tourVehicle.contains(beamVehicle.id) => Some(ActualVehicle(beamVehicle))
      case ActualVehicle(beamVehicle) if BeamVehicle.isSharedTeleportationVehicle(beamVehicle.id) =>
        Some(ActualVehicle(beamVehicle))
      case ActualVehicle(beamVehicle)
          if tourVehicle.isEmpty && tourMode.isDefined && beamVehicle.isMustBeDrivenHome && !onSubTour =>
        logger.debug(
          s"Person person ${this.id} is already on a walk based tour, and we have access to vehicle " +
          s" ${beamVehicle.id}, and we're" +
          " on the way home, but it is not our tour personal vehicle. Going to abandon it."
        )
        beamVehicles.remove(beamVehicle.id)
        None
      case ActualVehicle(beamVehicle)
          if tourVehicle
            .exists(newAndTourVehicles.contains) && BeamVehicle.isEmergencyVehicle(beamVehicle.id) =>
        logger.info(
          s"Person person ${this.id} is already on a car based tour, and we have access to vehicle " +
          s" ${beamVehicle.id}, and we shouldn't need it. Going to abandon it."
        )
        beamVehicles.remove(beamVehicle.id)
        None
      case ActualVehicle(beamVehicle) =>
        Some(ActualVehicle(beamVehicle))
      case otherVehicle =>
        Some(otherVehicle)
    }

  }

  /**
    * Checks to see what modes are allowed, given (1) What tour mode you are on, and (2) whether there are any tour
    * vehicles associated with your plan. 2 becomes important on WALK_BASED tours when you have used a vehicle for
    * initial access/egress
    */
  private def getAvailableModesGivenTourMode(
    availableModes: Seq[BeamMode],
    availablePersonalStreetVehicles: Vector[VehicleOrToken],
    currentTourMode: Option[BeamTourMode],
    nextActivity: Activity,
    maybeTourModeChoiceStrategy: Option[TourModeChoiceStrategy] = None,
    isSubTour: Boolean = false
  ): Seq[BeamMode] = {
    val maybeTourPersonalVehicle = maybeTourModeChoiceStrategy.flatMap(_.tourVehicle)
    availableModes.intersect(currentTourMode match {
      case Some(WALK_BASED)
          if availablePersonalStreetVehicles
            .exists(_.vehicle.isMustBeDrivenHome) && isLastTripWithinTour(nextActivity) && !isSubTour =>
        val requiredEgressModes = availablePersonalStreetVehicles.flatMap {
          case veh: ActualVehicle =>
            maybeTourPersonalVehicle match {
              case Some(tourVehicleId) if veh.id == tourVehicleId =>
                BeamTourMode.enabledModes.get(veh.streetVehicle.mode)
              case None if veh.vehicle.isMustBeDrivenHome =>
                None
              case Some(tourVehicleId) =>
                logger.debug(
                  s"Person person ${this.id} is on a walk tour with the wrong tour vehicle: $tourVehicleId when " +
                  s"we have access to ${veh.vehicle.id}. Should have already abandoned ${veh.vehicle.id}"
                )
                None
              case _ => Some(currentTourMode.map(_.allowedBeamModes).getOrElse(BeamMode.allModes))
            }
          case _ => None
        }.flatten
        requiredEgressModes
      case Some(tourMode) =>
        tourMode.allowedBeamModesGivenAvailableVehicles(
          availablePersonalStreetVehicles,
          isFirstOrLastTripWithinTour(nextActivity)
        )
      case None => BeamMode.allModes
    })
  }

  // Note that remainingAvailableVehicles includes all vehicles that were available,
  // and any unused vehicles will be released.
  // That's why we remove any drive_transit vehicles after
  // replanning -- so they don't get released.

  /**
    * Finds the set of modes that were queried based on the routing response, ride hail result,
    * and the optional ride hail to transit routing request identifier.
    *
    * @param routingResponse                  The response of a routing request, containing the requested modes and details about transit usage.
    * @param rideHailResult                   The result of a ride hail mode request, including whether the request was for pooled or non-pooled ride hail.
    * @param rideHail2TransitRoutingRequestId An optional identifier to determine if ride hail to transit was part of the query.
    * @return A set of Beam modes that were part of the query, which includes non-ride hail modes,
    *         direct ride hail modes, and ride hail transit modes.
    */
  private def findQueriedModes(
    routingResponse: RoutingResponse,
    rideHailResult: RideHailResponse,
    rideHail2TransitRoutingRequestId: Option[Int]
  ): Set[BeamMode] = {
    val expectedNonRideHailModes = routingResponse.request match {
      case Some(RoutingRequest(_, _, _, withTransit, _, streetVehicles, _, _, _, _, _)) if !withTransit =>
        streetVehicles.map(_.mode).toSet
      case Some(RoutingRequest(_, _, _, true, _, streetVehicles, _, _, _, _, _)) =>
        streetVehicles
          .map(_.mode)
          .flatMap {
            case CAR  => Seq(DRIVE_TRANSIT, CAR)
            case BIKE => Seq(BIKE_TRANSIT, BIKE)
            case WALK => Seq(WALK_TRANSIT, WALK)
            case _    => Seq.empty[BeamMode]
          }
          .toSet
      case _ => Set.empty[BeamMode]
    }
    val expectedDirectRideHailModes = rideHailResult.request match {
      case RideHailRequest(_, _, _, _, _, _, _, _, _, requestTime, _, _, _, _) if requestTime == -1 =>
        Set.empty[BeamMode]
      case RideHailRequest(_, _, _, _, _, asPooled, _, _, _, _, _, _, _, _) if asPooled =>
        Set(RIDE_HAIL_POOLED)
      case RideHailRequest(_, _, _, _, _, _, _, _, _, _, _, _, _, _) => Set(RIDE_HAIL)
    }

    val expectedRideHailTransitModes = rideHail2TransitRoutingRequestId match {
      case Some(_) => Set(RIDE_HAIL_TRANSIT)
      case None    => Set.empty[BeamMode]
    }

    expectedNonRideHailModes ++ expectedDirectRideHailModes ++ expectedRideHailTransitModes
  }

  /**
    * Handles the mode and vehicle choice for a person during their current activity in the simulation.
    * This function evaluates available transportation options, including ride-hail, walking, and other transit methods,
    * based on various criteria such as current location, tour strategy, and vehicle availability. It computes the
    * best possible trip options, filters them, and determines the next course of action based on the chosen alternative.
    * The method also updates the person's data with the selected trip mode and tour specifics.
    *
    * @return A transformation of the FSM state, applying the updated mode choice and trip information for the specific person
    *         if all conditions are met. Only states matching the specified input conditions are handled.
    */
  private def completeChoiceIfReady: PartialFunction[State, State] = {
    case FSM.State(
          _,
          choosesModeData @ ChoosesModeData(
            personData,
            _,
            None,
            Some(routingResponse),
            parkingResponses,
            parkingResponseIds,
            Some(rideHailResult),
            Some(rideHail2TransitRoutingResponse),
            rideHail2TransitRoutingRequestId,
            Some(rideHail2TransitAccessResult),
            _,
            Some(rideHail2TransitEgressResult),
            _,
            _,
            allAvailableStreetVehicles,
            _,
            _,
            Some(cavTripLegs),
            _,
            _,
            true,
            _
          ),
          _,
          _,
          _
        )
        if parkingResponses.size >= parkingResponseIds.size
          && allRequiredParkingResponsesReceived(routingResponse, parkingResponses) =>
      val currentPersonLocation = choosesModeData.currentLocation
      val nextAct = nextActivity(choosesModeData.personData).get
      val currentTourStrategy = _experiencedBeamPlan.getTourStrategy[TourModeChoiceStrategy](nextAct)
      val rideHail2TransitIineraries = createRideHail2TransitItin(
        rideHail2TransitAccessResult,
        rideHail2TransitEgressResult,
        rideHail2TransitRoutingResponse
      )

      val rideHailItinerary = rideHailResult.travelProposal match {
        case Some(travelProposal)
            if travelProposal.timeToCustomer(
              bodyVehiclePersonId
            ) <= travelProposal.maxWaitingTimeInSec =>
          travelProposalToRideHailLegs(
            travelProposal,
            rideHailResult.rideHailManagerName,
            choosesModeData.personData.currentTripMode
          )
            .map(surroundWithWalkLegsIfNeededAndMakeTrip)
        case _ =>
          Vector()
      }

      val combinedItinerariesForChoice = (rideHailItinerary ++ addParkingCostToItins(
        routingResponse.itineraries,
        parkingResponses
      ) ++ rideHail2TransitIineraries)
        .groupBy(t => (t.vehiclesInTrip, t.tripClassifier))
        .map(x => x._2.minBy(_.totalTravelTimeInSecs))
        .toVector

      def isAvailable(mode: BeamMode): Boolean = combinedItinerariesForChoice.exists(_.tripClassifier == mode)

      choosesModeData.personData.currentTripMode match {
        case Some(expectedMode) if !isAvailable(expectedMode) =>
          eventsManager.processEvent(
            createFailedODSkimmerEvent(currentActivity(personData), nextAct, expectedMode)
          )
        case _ =>
      }

      val availableParentTourVehicles = getParentTourStrategy(personData)
        .flatMap(strategy =>
          strategy.tourMode match {
            case Some(CAR_BASED | BIKE_BASED) =>
              strategy.tourVehicle
                .flatMap(v => beamVehicles.get(v))
                .filterNot(_.vehicle.isSharedVehicle)
            case _ =>
              None // If it's a walk_based tour we assume it was left at a transit stop en_route
          }
        )
        .toVector

      val newAndTourVehicles = allAvailableStreetVehicles ++ availableParentTourVehicles

      val availableEmergencyVehicles =
        beamVehicles.filterKeys(k => k.toString.startsWith(f"${this.id.toString}-emergency")).values.toVector

      val (chosenCurrentTourMode, chosenCurrentTourPersonalVehicle) =
        chooseTourModeAndVehicle(
          currentTourStrategy,
          choosesModeData.personData.currentTripMode,
          newAndTourVehicles ++ availableEmergencyVehicles,
          choosesModeData,
          combinedItinerariesForChoice
        )

      val parentTourStrategy = getParentTourStrategy(personData)

      val availableModesForTrips = getAvailableModesGivenTourMode(
        availableModesForPerson(matsimPlan.getPerson, choosesModeData.excludeModes),
        newAndTourVehicles,
        chosenCurrentTourMode,
        nextAct,
        Some(currentTourStrategy),
        parentTourStrategy.nonEmpty
      )

      if (availableModesForTrips.contains(DRIVE_TRANSIT) && parentTourStrategy.nonEmpty) {
        logger.debug("This is a strange situation to be worried about potentially")
      }

      val filteredItinerariesForChoice = choosesModeData.personData.currentTripMode match {
        case Some(mode) if mode == DRIVE_TRANSIT || mode == BIKE_TRANSIT =>
          (isFirstOrLastTripWithinTour(nextAct), personData.hasDeparted) match {
            case (true, false) =>
              combinedItinerariesForChoice.filter(_.tripClassifier == mode)
            case _ =>
              combinedItinerariesForChoice
          }
        case Some(mode) =>
          combinedItinerariesForChoice.filter(_.tripClassifier == mode)
        case _ =>
          combinedItinerariesForChoice
      }
      def getFailedBoardingVehicles(personData: BasePersonData): Set[Id[BeamVehicle]] = {
        // Get latest failed trip (which we're storing when boarding fails)
        personData.failedTrips.lastOption.flatMap { trip =>
          // Find first transit leg - this is the one that failed boarding
          trip.legs.find(_.beamLeg.mode.isTransit).map(_.beamVehicleId)
        }.toSet
      }

      val itinerariesOfCorrectMode =
        filteredItinerariesForChoice
          .filter(itin => availableModesForTrips.contains(itin.tripClassifier))
          .filterNot(itin =>
            itin.vehiclesInTrip
              .filterNot(_.toString.startsWith("body"))
              .exists(getFailedBoardingVehicles(personData).contains)
          )

      val currentAct = currentActivity(personData)

      if (beamServices.beamConfig.beam.exchange.output.activity_sim_skimmer.exists(_.primary.enabled)) {
        val queriedModes = findQueriedModes(routingResponse, rideHailResult, rideHail2TransitRoutingRequestId)

        // Find modes that were queried but don't have valid itineraries and report failures
        queriedModes.diff(combinedItinerariesForChoice.map(_.tripClassifier).toSet) foreach { beamMode =>
          val possibleActivitySimModes =
            determineActivitySimPathTypesFromBeamMode(Some(beamMode), Some(currentAct))

          createFailedActivitySimSkimmerEvent(currentAct, nextAct, possibleActivitySimModes).foreach(ev =>
            eventsManager.processEvent(ev)
          )
        }
      }

      val attributesOfIndividual =
        matsimPlan.getPerson.getCustomAttributes
          .get("beam-attributes")
          .asInstanceOf[AttributesOfIndividual]
      val availableAlts = Some(itinerariesOfCorrectMode.map(_.tripClassifier).mkString(":"))

      def gotoFinishingModeChoice(chosenTrip: EmbodiedBeamTrip) = {
        goto(FinishingModeChoice) using choosesModeData.copy(
          personData = personData.copy(
            currentTourMode = chosenCurrentTourMode,
            currentTripMode = Some(chosenTrip.tripClassifier),
            passengerSchedule = PassengerSchedule(),
            restOfCurrentTrip = List.empty[EmbodiedBeamLeg],
            currentTourPersonalVehicle = chosenCurrentTourMode match {
              // if they're on a walk based tour we let them keep access to whatever personal vehicle they used on the
              // first leg or in a parent tour
              case Some(WALK_BASED) => choosesModeData.personData.currentTourPersonalVehicle
              // Otherwise they keep track of the chosen vehicle
              case _ =>
                chosenCurrentTourPersonalVehicle
                  .get(chosenTrip)
                  .flatten // If we're on a subtour and it uses no vehicle, we still pass on any tour vehicle from parent tours
                  .orElse(
                    choosesModeData.personData.currentTourPersonalVehicle
                  )
            }
          ),
          pendingChosenTrip = Some(chosenTrip),
          availableAlternatives = availableAlts
        )
      }

      if (personData.numberOfReplanningAttempts > 20) {
        logger.warn(
          s"Agent ${this.id} exceeded 20 replanning attempts at ${choosesModeData.currentLocation}. " +
          s"Creating emergency walking trip. State: $choosesModeData"
        )

        val bushwhackingTrip = RoutingWorker.createBushwackingTrip(
          choosesModeData.currentLocation.loc,
          nextActivity(choosesModeData.personData).get.getCoord,
          _currentTick.get,
          body.toStreetVehicle,
          geo
        )

        gotoFinishingModeChoice(bushwhackingTrip)
      }

      val currentPlanMode = _experiencedBeamPlan
        .getStrategy[TripModeChoiceStrategy](_experiencedBeamPlan.getTripContaining(nextAct))
        .mode

      modeChoiceCalculator(
        itinerariesOfCorrectMode,
        attributesOfIndividual,
        nextActivity(choosesModeData.personData),
        Some(currentActivity(choosesModeData.personData)),
        Some(matsimPlan.getPerson)
      ) match {
        case Some(chosenTrip) if !currentPlanMode.contains(CAV) =>
          // Send non-chosen trips to skimmer if configured to do so
          combinedItinerariesForChoice.foreach {
            case possibleTrip
                if (possibleTrip != chosenTrip) && beamScenario.beamConfig.beam.router.skim.sendNonChosenTripsToSkimmer && !choosesModeData.personData.currentTourMode
                  .contains(FREIGHT_TOUR) =>
              generateSkimData(
                possibleTrip.legs.lastOption.map(_.beamLeg.endTime).getOrElse(_currentTick.get),
                possibleTrip,
                failedTrip = false,
                personData.currentActivityIndex,
                currentActivity(personData),
                nextActivity(personData)
              )
            case _ =>
          }
          if (
            currentTourStrategy.tourMode.isEmpty || (currentTourStrategy.tourMode.exists(
              _.isVehicleBased
            ) && currentTourStrategy.tourVehicle.isEmpty)
          ) {
            updateTourModeStrategy(
              chosenCurrentTourMode,
              chosenCurrentTourPersonalVehicle.getOrElse(chosenTrip, None),
              nextAct,
              newAndTourVehicles
            )
          }
          val dataForNextStep =
            choosesModeData.copy(
              personData = personData.copy(
                currentTourMode = chosenCurrentTourMode,
                currentTripMode = Some(chosenTrip.tripClassifier),
                currentTourPersonalVehicle = chosenCurrentTourPersonalVehicle
                  .get(chosenTrip)
                  .flatten // If we're on a subtour and it uses no vehicle, we still pass on any tour vehicle from parent tours
                  .orElse(personData.currentTourPersonalVehicle)
              ),
              pendingChosenTrip = Some(chosenTrip),
              availableAlternatives = availableAlts
            )
          goto(FinishingModeChoice) using dataForNextStep
        case None =>
          if (!choosesModeData.personData.currentTourMode.contains(FREIGHT_TOUR)) {
            combinedItinerariesForChoice.foreach { possibleTrip =>
              logger.debug(
                f"Sending trip ${possibleTrip} to skimmer because it didn't match required mode ${currentPlanMode}"
              )
              generateSkimData(
                routingResponse.request.map(_.departureTime).getOrElse(_currentTick.get),
                possibleTrip,
                failedTrip = false,
                personData.currentActivityIndex,
                currentActivity(personData),
                nextActivity(personData)
              )
            }
          }
          choosesModeData.personData.currentTripMode match {
            case Some(CAV) =>
              // Special case, if you are using household CAV, no choice was necessary you just use this mode
              // Construct the embodied trip to allow for processing by FinishingModeChoice and scoring
              if (cavTripLegs.legs.nonEmpty) {
                val walk1 = EmbodiedBeamLeg.dummyLegAt(
                  _currentTick.get,
                  body.id,
                  isLastLeg = false,
                  cavTripLegs.legs.head.beamLeg.travelPath.startPoint.loc,
                  WALK,
                  body.beamVehicleType.id
                )
                val walk2 = EmbodiedBeamLeg.dummyLegAt(
                  _currentTick.get + cavTripLegs.legs.map(_.beamLeg.duration).sum,
                  body.id,
                  isLastLeg = true,
                  cavTripLegs.legs.last.beamLeg.travelPath.endPoint.loc,
                  WALK,
                  body.beamVehicleType.id
                )
                val cavTrip = EmbodiedBeamTrip(walk1 +: cavTripLegs.legs.toVector :+ walk2)
                gotoFinishingModeChoice(cavTrip)
              } else {
                val bushwhackingTrip = RoutingWorker.createBushwackingTrip(
                  choosesModeData.currentLocation.loc,
                  nextActivity(choosesModeData.personData).get.getCoord,
                  _currentTick.get,
                  body.toStreetVehicle,
                  geo
                )
                gotoFinishingModeChoice(bushwhackingTrip)
              }
            case Some(mode @ (HOV2_TELEPORTATION | HOV3_TELEPORTATION)) =>
              logger.warn(
                f"Routing request for teleportation person ${this.id} failed. Creating a bushwhacking trip from " +
                f"$currentPersonLocation to ${nextAct.getCoord}"
              )
              val teleportationTripWithoutRoute = createExpensiveVehicleTrip(
                currentPersonLocation,
                nextAct,
                allAvailableStreetVehicles,
                routingResponse,
                mode match {
                  case HOV2_TELEPORTATION => CAR_HOV2
                  case _                  => CAR_HOV3
                }
              )
              gotoFinishingModeChoice(teleportationTripWithoutRoute)
            case Some(CAR) if choosesModeData.personData.currentTourMode.contains(FREIGHT_TOUR) =>
              logger.error(
                f"Routing request for freight agent ${this.id} failed. Creating a bushwhacking CAR trip from " +
                f"$currentPersonLocation to ${nextAct.getCoord}"
              )
              val expensiveFreightTrip =
                createExpensiveVehicleTrip(
                  currentPersonLocation,
                  nextAct,
                  allAvailableStreetVehicles,
                  routingResponse,
                  CAR
                )
              gotoFinishingModeChoice(expensiveFreightTrip)
            case Some(CAR)
                if newAndTourVehicles.isEmpty &&
                  beamScenario.beamConfig.beam.agentsim.agents.vehicles.generateEmergencyHouseholdVehicleWhenPlansRequireIt =>
              logger.warn(
                s"Person ${this.id} ended up stuck without a car despite having car in plans, so sending the request " +
                s"back through in order to create an emergency vehicle. Tick ${_currentTick.getOrElse(-1)} and " +
                s"activity ${_experiencedBeamPlan.getTripContaining(personData.currentActivityIndex)} " +
                s"of plan ${_experiencedBeamPlan.activities.map(_.getType)}. Available vehicles ${beamVehicles.keys.toString()}"
              )
              goto(ChoosingMode) using choosesModeData.copy(personData =
                personData.copy(currentTourPersonalVehicle = None)
              )
            case Some(mode) =>
              val odFailedSkimmerEvent = createFailedODSkimmerEvent(currentAct, nextAct, mode)
              eventsManager.processEvent(odFailedSkimmerEvent)

              // Generate activity sim failure events if enabled
              if (beamServices.beamConfig.beam.exchange.output.activity_sim_skimmer.exists(_.primary.enabled)) {
                val possibleActivitySimModes =
                  determineActivitySimPathTypesFromBeamMode(
                    choosesModeData.personData.currentTripMode,
                    Some(currentAct)
                  )
                createFailedActivitySimSkimmerEvent(currentAct, nextAct, possibleActivitySimModes).foreach(ev =>
                  eventsManager.processEvent(ev)
                )
              }

              // Create replanning event
              eventsManager.processEvent(
                new ReplanningEvent(
                  _currentTick.get,
                  Id.createPersonId(id),
                  getReplanningReasonFrom(
                    choosesModeData.personData,
                    ReservationErrorCode.RouteNotAvailableForChosenMode.entryName
                  ),
                  choosesModeData.currentLocation.loc.getX,
                  choosesModeData.currentLocation.loc.getY,
                  nextAct.getCoord.getX,
                  nextAct.getCoord.getY
                )
              )

              if (
                isFirstTripWithinTour(
                  currentActivity(choosesModeData.personData)
                ) && !choosesModeData.isWithinTripReplanning
              ) {
                logger.debug("Resetting tour mode to none because we haven't left yet")
                updateTourModeStrategy(
                  None,
                  None,
                  nextActivity(choosesModeData.personData).get,
                  choosesModeData.allAvailableStreetVehicles
                )
              }

              // Available vehicles filtering for replanning
              val availableVehicles =
                if (mode.isTeleportation)
                  // Remove teleportation vehicle since we can't use it for non-teleportation mode
                  choosesModeData.allAvailableStreetVehicles.filterNot(vehicle =>
                    BeamVehicle.isSharedTeleportationVehicle(vehicle.id)
                  )
                else choosesModeData.allAvailableStreetVehicles

              // If we've done a comprehensive routing query, we can reuse results without more routing
              if (
                choosesModeData.routingResponse.exists(
                  _.request.exists(_.withTransit)
                ) && choosesModeData.rideHail2TransitRoutingRequestId.nonEmpty && !choosesModeData.isWithinTripReplanning && personData.numberOfReplanningAttempts == 0
              ) {
                self ! RetryModeChoice(getCurrentTriggerId.get)
                val updatedTripStrategy = TripModeChoiceStrategy(None)
                _experiencedBeamPlan.putStrategy(
                  _experiencedBeamPlan.getTripContaining(nextActivity(choosesModeData.personData).get),
                  updatedTripStrategy
                )

                stay() using choosesModeData.copy(
                  personData = personData.copy(
                    currentTripMode = None,
                    numberOfReplanningAttempts = personData.numberOfReplanningAttempts + 1
                  ),
                  allAvailableStreetVehicles = availableVehicles,
                  routingFinished = true,
                  excludeModes = choosesModeData.excludeModes ++ choosesModeData.personData.currentTripMode
                )
              } else {
                val (updatedVehicles, currentTourVehicle) =
                  if (
                    (mode == DRIVE_TRANSIT || mode == BIKE_TRANSIT) && (isLastTripWithinTour(
                      nextAct
                    ) || personData.numberOfReplanningAttempts > 5) && personData.currentTourPersonalVehicle.isDefined
                  ) {
                    // Abandon the vehicle because we have no route to get it home
                    val vehicleId = personData.currentTourPersonalVehicle.get
                    logger.warn(
                      s"Agent ${this.id} is abandoning vehicle $vehicleId after ${personData.numberOfReplanningAttempts + 1} " +
                      s"failed attempts to find a route to take it home on a ${mode.toString} trip."
                    )

                    val remainingVehicles = availableVehicles.filterNot(v => v.id == vehicleId)
                    updateTourModeStrategy(
                      currentTourStrategy.tourMode,
                      None,
                      nextActivity(choosesModeData.personData).get,
                      remainingVehicles
                    )
                    // Release the vehicle
                    if (beamVehicles.contains(vehicleId)) {
                      val vehicle = beamVehicles(vehicleId).vehicle
                      vehicle.setMustBeDrivenHome(false)
                      vehicle.unsetDriver()
                      beamVehicles.remove(vehicleId)
                    }
                    (remainingVehicles, None)
                  } else {
                    (availableVehicles, personData.currentTourPersonalVehicle)
                  }
                // Need to gather more routing options
                self ! MobilityStatusResponse(availableVehicles, getCurrentTriggerId.get)
                logger.debug(
                  "Person {} replanning because planned mode {} not available",
                  body.id,
                  mode.toString
                )
                val updatedTripStrategy = TripModeChoiceStrategy(None)
                _experiencedBeamPlan.putStrategy(
                  _experiencedBeamPlan.getTripContaining(nextActivity(choosesModeData.personData).get),
                  updatedTripStrategy
                )
                stay() using ChoosesModeData(
                  personData = personData.copy(
                    currentTripMode = None,
                    numberOfReplanningAttempts = personData.numberOfReplanningAttempts + 1,
                    currentTourPersonalVehicle = currentTourVehicle
                  ),
                  allAvailableStreetVehicles = updatedVehicles,
                  currentLocation = choosesModeData.currentLocation,
                  excludeModes = choosesModeData.excludeModes ++ choosesModeData.personData.currentTripMode,
                  parkingRequestIds = Map.empty // Clear any pending parking requests
                )
              }
            case _ =>
              // Bad things happen but we want them to continue their day, so we signal to downstream that trip should be made to be expensive
              val expensiveWalkTrip = createExpensiveWalkTrip(currentPersonLocation, nextAct, routingResponse)
              gotoFinishingModeChoice(expensiveWalkTrip)
          }
      }
  }

  private def createExpensiveVehicleTrip(
    currentPersonLocation: SpaceTime,
    nextAct: Activity,
    availableStreetVehicles: Vector[VehicleOrToken],
    routingResponse: RoutingResponse,
    mode: BeamMode
  ) = {
    availableStreetVehicles.find(_.streetVehicle.mode == CAR) match {
      case Some(availableVehicle) =>
        val bushwhackingLeg = RoutingWorker
          .createBushwackingTrip(
            currentPersonLocation.loc,
            nextAct.getCoord,
            _currentTick.get,
            availableVehicle.streetVehicle,
            beamServices.geo,
            mode = mode,
            unbecomeDriverOnCompletion = false
          )
          .legs
          .head
        EmbodiedBeamTrip(
          Vector(
            EmbodiedBeamLeg.dummyLegAt(
              _currentTick.get,
              body.id,
              isLastLeg = false,
              beamServices.geo.utm2Wgs(currentPersonLocation.loc),
              WALK,
              body.beamVehicleType.id
            )
          ) :+ bushwhackingLeg :+
          EmbodiedBeamLeg.dummyLegAt(
            _currentTick.get + bushwhackingLeg.beamLeg.duration,
            availableVehicle.id,
            isLastLeg = true,
            beamServices.geo.utm2Wgs(nextAct.getCoord),
            mode,
            availableVehicle.vehicle.beamVehicleType.id
          ) :+ EmbodiedBeamLeg.dummyLegAt(
            _currentTick.get + bushwhackingLeg.beamLeg.duration,
            body.id,
            isLastLeg = true,
            beamServices.geo.utm2Wgs(nextAct.getCoord),
            WALK,
            body.beamVehicleType.id
          )
        )
      case _ =>
        logger.warn(
          f"Failed to create bushwhacking vehicle trip for agent ${routingResponse.request.flatMap(_.personId)} " +
          "because no  vehicle are available"
        )
        createExpensiveWalkTrip(currentPersonLocation, nextAct, routingResponse)
    }
  }

  private def createExpensiveWalkTrip(
    currentPersonLocation: SpaceTime,
    nextAct: Activity,
    routingResponse: RoutingResponse
  ) = {
    val originalWalkTripLeg =
      routingResponse.itineraries.find(_.tripClassifier == WALK) match {
        case Some(originalWalkTrip) =>
          originalWalkTrip.legs.head
        case None =>
          RoutingWorker
            .createBushwackingTrip(
              currentPersonLocation.loc,
              nextAct.getCoord,
              _currentTick.get,
              body.toStreetVehicle,
              beamServices.geo
            )
            .legs
            .head
      }
    val minDuration =
      if (originalWalkTripLeg.beamLeg.duration < beamServices.beamConfig.beam.agentsim.schedulerParallelismWindow) {
        logger.info(
          s"Agent ${this.id}'s walk trip duration ${originalWalkTripLeg.beamLeg.duration} is less than the minimum " +
          s"of ${beamServices.beamConfig.beam.agentsim.schedulerParallelismWindow}. Setting it to the minimum."
        )
        beamServices.beamConfig.beam.agentsim.schedulerParallelismWindow
      } else {
        originalWalkTripLeg.beamLeg.duration
      }

    val expensiveWalkTrip = EmbodiedBeamTrip(
      Vector(
        originalWalkTripLeg.copy(
          replanningPenalty = 10.0,
          beamLeg = originalWalkTripLeg.beamLeg.scaleToNewDuration(minDuration)
        )
      )
    )
    expensiveWalkTrip
  }

  private def gotoChoosingModeWithoutPredefinedMode(choosesModeData: ChoosesModeData) = {
    // TODO: Check modes for subsequent trips here
    val onFirstTripWithinTour: Boolean = isFirstTripWithinTour(currentActivity(choosesModeData.personData))
    val withinReplanning: Boolean = choosesModeData.isWithinTripReplanning
    val agentStillAtTourOrigin: Boolean = onFirstTripWithinTour && !withinReplanning
    val outcomeTourMode = if (agentStillAtTourOrigin) { None }
    else { Some(WALK_BASED) }
    val parentTourVehicle = getParentTourStrategy(choosesModeData.personData).flatMap(_.tourVehicle)
    val isAccessEgressInTour: Boolean = choosesModeData.personData.currentTourMode.contains(WALK_BASED)
    val newTourVehicle = choosesModeData.personData.currentTourPersonalVehicle match {
      case Some(id) if beamVehicles.contains(id) =>
        if (isAccessEgressInTour && !agentStillAtTourOrigin) {
          /*
           * This code block only runs when someone needs to re-plan and re-do mode choice.
           * If for instance they were going to take a bike trip but no bike route was available
           * they need to release the bike so others can use it.
           * But if they're in the middle of a tour and just can't find a transit route,
           * for instance, but they took drive_transit on their first leg and need to take it home,
           * we keep the original vehicle in beamVehicles so we can use it later
           *
           * The problem is that when someone gets a resourceCapacityExhausted error on the first leg of a drive_transit tour,
           * the existing logic thinks that we're in the first scenario (didn't use a vehicle so we can release it)
           * rather than the second one (have already used a vehicle and need to return to it at the end of our tour).
           *
           * For that matter, we are adding "choosesModeData.isWithinTripReplanning". As long as we are still replanning
           * we don't release the vehicle until their last tour trip of their tour.
           *
           * e.g., they'll just get on the next train, go about their drive_transit tour, and
           * then take drive_transit as the mode for the last leg of their tour and pick up their car on the way home
           * */
          Some(id)
        } else if (parentTourVehicle.isEmpty) {
          val vehicle = beamVehicles(id).vehicle
          vehicle.setMustBeDrivenHome(false)
          beamVehicles.remove(vehicle.id)
          vehicle.getManager.get ! ReleaseVehicle(vehicle, getCurrentTriggerId.get)
          if (!agentStillAtTourOrigin) {
            logger.warn(
              s"Abandoning vehicle $id because no return ${choosesModeData.personData.currentTripMode} " +
              s"itinerary is available"
            )
          } else {
            logger.debug(
              s"Not keeping vehicle $id because no  ${choosesModeData.personData.currentTripMode} " +
              s"is available"
            )
          }
          None
        } else {
          parentTourVehicle
        }
      case _ => None
    }

    if (choosesModeData.personData.currentTripMode.exists(_.isTeleportation)) {
      //we need to remove our teleportation vehicle since we cannot use it if it's not a teleportation mode {
      val availableVehicles = choosesModeData.allAvailableStreetVehicles.filterNot(vehicle =>
        BeamVehicle.isSharedTeleportationVehicle(vehicle.id)
      )
      self ! MobilityStatusResponse(availableVehicles, getCurrentTriggerId.get)
      stay()
    } else {
      val updatedTripStrategy = TripModeChoiceStrategy(None)
      _experiencedBeamPlan.putStrategy(
        _experiencedBeamPlan.getTripContaining(nextActivity(choosesModeData.personData).get),
        updatedTripStrategy
      )
      updateTourModeStrategy(
        outcomeTourMode,
        newTourVehicle,
        nextActivity(choosesModeData.personData).get,
        choosesModeData.allAvailableStreetVehicles
      )
      goto(ChoosingMode)
    } using choosesModeData.copy(
      personData = choosesModeData.personData.copy(
        currentTripMode = None,
        currentTourMode = outcomeTourMode,
        currentTrip = None,
        restOfCurrentTrip = List.empty,
        currentTourPersonalVehicle = newTourVehicle,
        numberOfReplanningAttempts = choosesModeData.personData.numberOfReplanningAttempts + 1
      ),
      currentLocation = choosesModeData.currentLocation,
      excludeModes = choosesModeData.excludeModes ++ choosesModeData.personData.currentTripMode
    )
  }

  /**
    * Creates None, RIDE_HAIL, RIDE_HAIL_POOLED or both legs from a TravelProposal
    * @param travelProposal the proposal
    * @param requiredMode the required mode of the trip. If it's None then both modes are possible.
    * @return An empty vector in case it cannot satisfy conditions.
    */
  private def travelProposalToRideHailLegs(
    travelProposal: RideHailManager.TravelProposal,
    rideHailMangerName: String,
    requiredMode: Option[BeamMode]
  ) = {
    val origLegs = travelProposal.toEmbodiedBeamLegsForCustomer(bodyVehiclePersonId, rideHailMangerName)
    travelProposal.poolingInfo match {
      case Some(poolingInfo)
          if !requiredMode.contains(RIDE_HAIL)
            && travelProposal.modeOptions.contains(RIDE_HAIL_POOLED) =>
        val pooledLegs = origLegs.map { origLeg =>
          if (origLeg.isRideHail)
            origLeg.copy(
              cost = origLeg.cost * poolingInfo.costFactor,
              isPooledTrip = true,
              beamLeg = origLeg.beamLeg.scaleLegDuration(poolingInfo.timeFactor)
            )
          else origLeg
        }
        val consistentPooledLegs = EmbodiedBeamLeg.makeLegsConsistent(pooledLegs)
        if (travelProposal.modeOptions.contains(RIDE_HAIL) && !requiredMode.contains(RIDE_HAIL_POOLED)) {
          Vector(origLegs, consistentPooledLegs)
        } else {
          Vector(consistentPooledLegs)
        }
      case _
          if !requiredMode.contains(RIDE_HAIL_POOLED)
            && travelProposal.modeOptions.contains(RIDE_HAIL) =>
        Vector(origLegs)
      case _ =>
        // required mode doesn't correspond to mode options provided by travel proposal
        Vector()
    }
  }

  private def surroundWithWalkLegsIfNeededAndMakeTrip(partialItin: Vector[EmbodiedBeamLeg]): EmbodiedBeamTrip = {
    val firstLegWalk = partialItin.head.beamLeg.mode == WALK
    val lastLegWalk = partialItin.last.beamLeg.mode == WALK
    val startLeg: Option[EmbodiedBeamLeg] =
      if (firstLegWalk) None
      else
        Some(
          EmbodiedBeamLeg.dummyLegAt(
            start = _currentTick.get,
            vehicleId = body.id,
            isLastLeg = false,
            location = partialItin.head.beamLeg.travelPath.startPoint.loc,
            mode = WALK,
            vehicleTypeId = body.beamVehicleType.id
          )
        )
    val endLeg =
      if (lastLegWalk) None
      else
        Some(
          EmbodiedBeamLeg.dummyLegAt(
            start = partialItin.last.beamLeg.endTime,
            vehicleId = body.id,
            isLastLeg = true,
            location = partialItin.last.beamLeg.travelPath.endPoint.loc,
            mode = WALK,
            vehicleTypeId = body.beamVehicleType.id
          )
        )
    EmbodiedBeamTrip((startLeg ++: partialItin) ++ endLeg)
  }

  private def createFailedODSkimmerEvent(
    originActivity: Activity,
    destinationActivity: Activity,
    mode: BeamMode
  ): ODSkimmerFailedTripEvent = {
    val geoMap = beamScenario.tazTreeMap
    val (origin, destination) = if (geoMap.tazListContainsGeoms) {
      val origGeo = getTazFromActivity(originActivity, geoMap).toString
      val destGeo = getTazFromActivity(destinationActivity, geoMap).toString
      (origGeo, destGeo)
    } else {
      (
        geoMap.getTAZ(originActivity.getCoord).tazId.toString,
        geoMap.getTAZ(destinationActivity.getCoord).tazId.toString
      )
    }

    ODSkimmerFailedTripEvent(
      origin = origin,
      destination = destination,
      eventTime = _currentTick.get,
      mode = mode,
      beamServices.matsimServices.getIterationNumber,
      skimName = beamServices.beamConfig.beam.router.skim.origin_destination_skimmer.name
    )
  }

  private def createFailedActivitySimSkimmerEvent(
    currentAct: Activity,
    nextAct: Activity,
    modes: Seq[ActivitySimPathType]
  ): Seq[ActivitySimSkimmerFailedTripEvent] = {
    val (origin, destination) = getOriginAndDestinationFromGeoMap(currentAct, Some(nextAct))
    modes.flatMap { pathType =>
      rideHailModeToFleets.get(pathType) match {
        case Some(fleets) =>
          fleets.map(fleet =>
            ActivitySimSkimmerFailedTripEvent(
              origin = origin,
              destination = destination,
              eventTime = _currentTick.get,
              activitySimPathType = pathType,
              fleet = Some(fleet),
              iterationNumber = beamServices.matsimServices.getIterationNumber,
              skimName = beamServices.beamConfig.beam.router.skim.activity_sim_skimmer.name
            )
          )
        case _ =>
          Seq(
            ActivitySimSkimmerFailedTripEvent(
              origin = origin,
              destination = destination,
              eventTime = _currentTick.get,
              activitySimPathType = pathType,
              fleet = None,
              iterationNumber = beamServices.matsimServices.getIterationNumber,
              skimName = beamServices.beamConfig.beam.router.skim.activity_sim_skimmer.name
            )
          )
      }
    }
  }

  private def allRequiredParkingResponsesReceived(
    routingResponse: RoutingResponse,
    parkingResponses: Map[VehicleOnTrip, ParkingInquiryResponse]
  ): Boolean = {
    val actualVehiclesToBeParked: Seq[VehicleOnTrip] = routingResponse.itineraries
      .flatMap { trip =>
        trip.legs
          .filter(leg => legVehicleHasParkingBehavior(leg))
          .map(leg => VehicleOnTrip(leg.beamVehicleId, TripIdentifier(trip)))
      }

    actualVehiclesToBeParked.forall(parkingResponses.contains)
  }

  when(FinishingModeChoice, stateTimeout = Duration.Zero) {
    case Event(rhr: RideHailResponse, data: ChoosesModeData) =>
      logger.warn(
        s"Recieved a ride hail response even though we'd already moved on after choosing our mode. " +
        s"Response: $rhr"
      )
      stay using data
    case Event(StateTimeout, data: ChoosesModeData) =>
      val pendingTrip = data.pendingChosenTrip.get
      val (tick, triggerId) = releaseTickAndTriggerId()
      val originActivity = currentActivity(data.personData)
      val correctedActivityEndTime =
        calculateActivityEndTime(originActivity, originActivity.getStartTime.orElse(tick.toDouble))
      val chosenTrip =
        makeFinalCorrections(pendingTrip, tick, correctedActivityEndTime)

      // Write start and end links of chosen route into Activities.
      // We don't check yet whether the incoming and outgoing routes agree on the link an Activity is on.
      // Our aim should be that every transition from a link to another link be accounted for.
      val headOpt = chosenTrip.legs.headOption
        .flatMap(_.beamLeg.travelPath.linkIds.headOption)
      val lastOpt = chosenTrip.legs.lastOption
        .flatMap(_.beamLeg.travelPath.linkIds.lastOption)
      if (headOpt.isDefined && lastOpt.isDefined) {
        _experiencedBeamPlan
          .activities(data.personData.currentActivityIndex)
          .setLinkId(Id.createLinkId(headOpt.get))
        _experiencedBeamPlan
          .activities(data.personData.currentActivityIndex + 1)
          .setLinkId(Id.createLinkId(lastOpt.get))
      } else {
        val origin = beamServices.geo.utm2Wgs(
          _experiencedBeamPlan
            .activities(data.personData.currentActivityIndex)
            .getCoord
        )
        val destination = beamServices.geo.utm2Wgs(
          _experiencedBeamPlan
            .activities(data.personData.currentActivityIndex + 1)
            .getCoord
        )
        val linkRadiusMeters = beamScenario.beamConfig.beam.routing.r5.linkRadiusMeters
        _experiencedBeamPlan
          .activities(data.personData.currentActivityIndex)
          .setLinkId(
            Id.createLinkId(
              beamServices.geo.getNearestR5Edge(transportNetwork.streetLayer, origin, linkRadiusMeters)
            )
          )
        _experiencedBeamPlan
          .activities(data.personData.currentActivityIndex + 1)
          .setLinkId(
            Id.createLinkId(
              beamServices.geo.getNearestR5Edge(transportNetwork.streetLayer, destination, linkRadiusMeters)
            )
          )
      }

      val tripId: String = _experiencedBeamPlan.trips
        .lift(data.personData.currentActivityIndex + 1) match {
        case Some(trip) =>
          trip.leg.map(l => Option(l.getAttributes.getAttribute("trip_id")).getOrElse("").toString).getOrElse("")
        case None => ""
      }

      val destinationActivity = nextActivity(data.personData).get
      val isFirstTrip = isFirstTripWithinTour(destinationActivity)
      val isLastTrip = isLastTripWithinTour(destinationActivity)

      val initialTourMode = data.personData.currentTourMode

      val modeChoiceEvent = new ModeChoiceEvent(
        tick,
        id,
        chosenTrip.tripClassifier.value,
        initialTourMode.map(_.value).getOrElse(""),
        data.expectedMaxUtilityOfLatestChoice.getOrElse[Double](Double.NaN),
        _experiencedBeamPlan.activities(data.personData.currentActivityIndex).getLinkId.toString,
        data.availableAlternatives.get,
        data.availablePersonalStreetVehicles.nonEmpty,
        chosenTrip.legs.view.map(_.beamLeg.travelPath.distanceInM).sum,
        _experiencedBeamPlan.tourIndexOfElement(destinationActivity),
        chosenTrip,
        _experiencedBeamPlan.activities(data.personData.currentActivityIndex).getType,
        destinationActivity.getType,
        tripId
      )
      eventsManager.processEvent(modeChoiceEvent)

      data.personData.currentTripMode match {
        case Some(mode) if mode.isTeleportation =>
          scheduler ! CompletionNotice(
            triggerId,
            Vector(
              ScheduleTrigger(
                PersonDepartureTrigger(math.max(chosenTrip.legs.head.beamLeg.startTime, tick)),
                self
              )
            )
          )

          val updatedTripStrategy =
            TripModeChoiceStrategy(Some(chosenTrip.tripClassifier))
          _experiencedBeamPlan.putStrategy(
            _experiencedBeamPlan.getTripContaining(destinationActivity),
            updatedTripStrategy
          )

          goto(Teleporting) using data.personData.copy(
            currentTrip = Some(chosenTrip),
            restOfCurrentTrip = List()
          )

        case _ =>
          val (vehiclesUsed, vehiclesNotUsed) = data.availablePersonalStreetVehicles
            .partition(vehicle => chosenTrip.vehiclesInTrip.contains(vehicle.id))

          vehiclesUsed.foreach {
            case veh if !beamVehicles.contains(veh.id) =>
              logger.error("Why is a vehicle that is used not in beamVehicles")
            case _ =>
          }

          var isCurrentPersonalVehicleVoided = false
          vehiclesNotUsed.collect {
            case ActualVehicle(vehicle) if data.personData.currentTourPersonalVehicle.contains(vehicle.id) =>
              if (data.personData.currentTourMode.contains(WALK_BASED)) {
                // Note: Removed this condition: !isFirstTripWithinTour(destinationActivity)
                if (
                  getCurrentTourStrategy(data.personData).tourVehicle.contains(
                    vehicle.id
                  ) || data.isWithinTripReplanning
                ) {
                  logger.debug(
                    s"Person ${this.id} is keeping vehicle ${vehicle.id} even though it isn't used in this trip " +
                    s"because we need it for egress at the end of the tour"
                  )
                } else if (getParentTourStrategy(data.personData).isEmpty) {
                  logger.warn(
                    s"Person ${this.id} is keeping vehicle ${vehicle.id} even though it's not stored in our " +
                    s"tourModeStrategy, which is ${getCurrentTourStrategy(data.personData)}"
                  )
                }
              } else if (getParentTourStrategy(data.personData).exists(s => s.tourVehicle.contains(vehicle.id))) {
                logger.debug(
                  s"We're keeping vehicle ${vehicle.id} even though it isn't used in this trip " +
                  s"because we need it in our parent tour"
                )
              } else {
                if (!data.isWithinTripReplanning) {
                  logger.warn(
                    s"Person ${this.id} is going to give up vehicle " +
                    s"${vehicle.id} because it's not used in our next leg. Perhaps it was created unnecessarily? - $data"
                  )
                }
                isCurrentPersonalVehicleVoided = true
                vehicle.setMustBeDrivenHome(false)
                beamVehicles.remove(vehicle.id)
                vehicle.getManager.get ! ReleaseVehicle(vehicle, triggerId)
              }
            case ActualVehicle(vehicle)
                if getParentTourStrategy(data.personData).exists(s => s.tourVehicle.contains(vehicle.id)) =>
              logger.warn {
                f"Keeping vehicle ${vehicle.id} because it's used in a parent tour, " +
                f"but it really should be in personData for person ${this.id}"
              }
            case ActualVehicle(vehicle) if beamVehicles.contains(vehicle.id) =>
              beamVehicles.remove(vehicle.id)
              vehicle.getManager match {
                case Some(manager) if BeamVehicle.isEmergencyVehicle(vehicle.id) && !isLastTrip =>
                  logger.debug(f"Releasing emergency vehicle for person ${this.id}")
                  manager ! ReleaseVehicle(vehicle, triggerId)
                case Some(manager) => manager ! ReleaseVehicle(vehicle, triggerId)
                case _             => logger.warn(s"Giving up vehicle ${vehicle.id}, which doesn't have a manager set")
              }
            case ActualVehicle(vehicle) =>
              logger.info(f"This should have already been deleted: ${vehicle.id}")
            case _ =>
              logError("We should only have real vehicles returned by manager")
          }

          scheduler ! CompletionNotice(
            triggerId,
            Vector(
              ScheduleTrigger(
                PersonDepartureTrigger(math.max(chosenTrip.legs.head.beamLeg.startTime, tick)),
                self
              )
            )
          )

          val currentTourPersonalVehicle = {
            if (isCurrentPersonalVehicleVoided)
              vehiclesUsed.headOption
                .filter(v => !v.vehicle.isSharedVehicle || BeamVehicle.isEmergencyVehicle(v.id))
                .map(_.id)
            else {
              data.personData.currentTourPersonalVehicle
                .orElse(
                  vehiclesUsed.view
                    .filter(!_.vehicle.isSharedVehicle)
                    .find { veh =>
                      (chosenTrip.tripClassifier, data.personData.currentTourMode) match {
                        case (_, Some(FREIGHT_TOUR)) => veh.vehicle.isFreightVehicle
                        case (_, Some(CAR_BASED))    => veh.vehicle.beamVehicleType.vehicleCategory == VehicleCategory.Car
                        case (_, Some(BIKE_BASED)) =>
                          veh.vehicle.beamVehicleType.vehicleCategory == VehicleCategory.Bike
                        case (DRIVE_TRANSIT, _) => veh.vehicle.beamVehicleType.vehicleCategory == VehicleCategory.Car
                        case (BIKE_TRANSIT, _)  => veh.vehicle.beamVehicleType.vehicleCategory == VehicleCategory.Bike
                        case _                  => false
                      }
                    }
                    .map(_.id)
                )
            }
          }

          val currentPlanMode = _experiencedBeamPlan
            .getStrategy[TripModeChoiceStrategy](_experiencedBeamPlan.getTripContaining(destinationActivity))
            .mode

          // Manually set that personal bike transit vehicles must be driven home because it's not handled in parking
          currentTourPersonalVehicle match {
            case Some(veh)
                if currentPlanMode
                  .contains(BIKE_TRANSIT) && isFirstTripWithinTour(destinationActivity) && !beamVehicles(
                  veh
                ).vehicle.isSharedVehicle =>
              beamVehicles(veh).vehicle.setMustBeDrivenHome(true)
            case Some(veh)
                if currentPlanMode.contains(BIKE_TRANSIT) && isLastTripWithinTour(destinationActivity) && !beamVehicles(
                  veh
                ).vehicle.isSharedVehicle =>
              beamVehicles(veh).vehicle.setMustBeDrivenHome(false)
            case _ =>
          }

          currentPlanMode match {
            case None =>
              _experiencedBeamPlan.putStrategy(
                _experiencedBeamPlan.getTripContaining(destinationActivity),
                TripModeChoiceStrategy(Some(chosenTrip.tripClassifier))
              )
            case Some(strategyMode) if strategyMode == chosenTrip.tripClassifier =>
            case Some(strategyMode @ (DRIVE_TRANSIT | BIKE_TRANSIT | RIDE_HAIL_TRANSIT))
                if (chosenTrip.tripClassifier == WALK_TRANSIT) && data.isWithinTripReplanning =>
              logger.debug(f"Assigning replanning walk_transit trip as part of planned $strategyMode trip")
            case Some(otherMode) if currentTourPersonalVehicle.isDefined & isLastTrip =>
              logger.warn(
                s"Chose a ${chosenTrip.tripClassifier} trip with a $otherMode leg in our plans. This is because " +
                s"we need to take tour vehicle ${currentTourPersonalVehicle.get} back home. Updating it in plan"
              )
              _experiencedBeamPlan.putStrategy(
                _experiencedBeamPlan.getTripContaining(destinationActivity),
                TripModeChoiceStrategy(Some(chosenTrip.tripClassifier))
              )
            case Some(otherMode) =>
              logger.error(
                s"Unexpected difference between trip modes in plans: Chose a ${chosenTrip.tripClassifier} " +
                s"trip with a $otherMode leg in our plans. ChoosesModeData: $data"
              )
          }

          goto(WaitingForDeparture) using data.personData.copy(
            currentTrip = Some(chosenTrip),
            restOfCurrentTrip = chosenTrip.legs.toList,
            currentTripMode = Some(chosenTrip.tripClassifier),
            currentTourPersonalVehicle = currentTourPersonalVehicle,
            failedTrips = data.personData.failedTrips ++ data.personData.currentTrip
          )
      }
  }

  private def makeFinalCorrections(trip: EmbodiedBeamTrip, tick: Int, currentActivityEndTime: Double) = {
    val startTimeUpdated =
      if (trip.tripClassifier.isTransit && trip.legs.head.beamLeg.startTime > tick) {
        //we need to start trip as soon as our activity finishes (current tick) in order to
        //correctly show waiting time for the transit in the OD skims
        val legStartTime = Math.max(tick, currentActivityEndTime)
        trip.updatePersonalLegsStartTime(legStartTime.toInt)
      } else {
        trip
      }
    // person should unbecome driver of his body only at the last walk leg
    val lastLeg = startTimeUpdated.legs.last
    if (lastLeg.is(WALK)) {
      startTimeUpdated.copy(legs = startTimeUpdated.legs.map { leg =>
        if (leg.is(WALK) && leg != lastLeg && leg.unbecomeDriverOnCompletion)
          leg.copy(unbecomeDriverOnCompletion = false)
        else
          leg
      })
    } else {
      startTimeUpdated
    }
  }

  /**
    * Constructs and sends routing and mode choice requests to the appropriate services (e.g., router, ride hail manager).
    *
    * @param currentTripMode   Optionally, the current mode of transportation for the trip.
    * @param currentTourMode   Optionally, the overall mode chosen for the tour.
    * @param hasRideHail       A flag indicating if ride hail services are available.
    * @param availableVehicles A vector of available vehicles or tokens for the current person.
    * @param choosesModeData   Data structure containing information for mode choice decision-making.
    * @param triggerId         An identifier for the triggering event of the requests.
    * @return A tuple containing placeholders for chooses mode response, an optional request ID, and an updated vector of vehicles or tokens.
    */
  private def makeRoutingRequests(
    currentTripMode: Option[BeamMode],
    currentTourMode: Option[BeamTourMode],
    hasRideHail: Boolean,
    availableVehicles: Vector[VehicleOrToken],
    choosesModeData: ChoosesModeData,
    triggerId: Long
  ): (ChoosesModeResponsePlaceholders, Option[Int], Vector[VehicleOrToken]) = {

    val currentPersonLocation = choosesModeData.currentLocation
    val availableModes: Seq[BeamMode] = availableModesForPerson(matsimPlan.getPerson, choosesModeData.excludeModes)
    val nextAct = nextActivity(choosesModeData.personData).get
    val departTime = _currentTick.get
    val bodyStreetVehicle = createBodyStreetVehicle(currentPersonLocation)
    var resetVehicles = false

    def makeRequestWith(
      withTransit: Boolean,
      vehicles: Vector[StreetVehicle],
      streetVehiclesIntermodalUse: IntermodalUse = Access,
      possibleEgressVehicles: IndexedSeq[StreetVehicle] = IndexedSeq.empty,
      departureBuffer: Int = 0
    ): Unit = {
      router ! RoutingRequest(
        currentPersonLocation.loc,
        nextAct.getCoord,
        departTime + departureBuffer,
        withTransit,
        Some(id),
        vehicles,
        Some(attributes),
        streetVehiclesIntermodalUse,
        possibleEgressVehicles = possibleEgressVehicles,
        triggerId = getCurrentTriggerIdOrGenerate
      )
    }

    def makeRideHailRequest(): Unit = {
      val inquiry = RideHailRequest(
        RideHailInquiry,
        bodyVehiclePersonId,
        currentPersonLocation.loc,
        departTime,
        nextAct.getCoord,
        withWheelchair = wheelchairUser,
        requestTime = _currentTick.get,
        requester = self,
        rideHailServiceSubscription = attributes.rideHailServiceSubscription,
        triggerId = getCurrentTriggerIdOrGenerate,
        asPooled = !choosesModeData.personData.currentTripMode.contains(RIDE_HAIL)
      )
      rideHailManager ! inquiry
    }

    def makeRideHailTransitRoutingRequest(bodyStreetVehicleRequestParam: StreetVehicle): Option[Int] = {
      //TODO make ride hail wait buffer config param
      val startWithWaitBuffer = 900 + departTime
      val currentSpaceTime =
        SpaceTime(currentPersonLocation.loc, startWithWaitBuffer)
      val theRequest = RoutingRequest(
        currentSpaceTime.loc,
        nextAct.getCoord,
        startWithWaitBuffer,
        withTransit = true,
        Some(id),
        Vector(bodyStreetVehicleRequestParam, dummyRHVehicle.copy(locationUTM = currentSpaceTime)),
        streetVehiclesUseIntermodalUse = rideHailTransitIntermodalUse,
        triggerId = getCurrentTriggerIdOrGenerate
      )
      router ! theRequest
      Some(theRequest.requestId)
    }

    def filterStreetVehiclesForQuery(
      streetVehicles: Vector[StreetVehicle],
      byMode: BeamMode
    ): Vector[StreetVehicle] = {
      choosesModeData.personData.currentTourPersonalVehicle match {
        case Some(personalVeh) =>
          // We already have a vehicle we're using on this tour, so filter down to that
          streetVehicles.filter(_.id == personalVeh)
        case None =>
          // Otherwise, filter by mode
          streetVehicles.filter(_.mode == byMode)
      }
    }

    val availableModesGivenTourMode = getAvailableModesGivenTourMode(
      availableModes,
      availableVehicles,
      currentTourMode,
      nextAct,
      Some(getCurrentTourStrategy(choosesModeData.personData))
    )

    var responsePlaceholders = ChoosesModeResponsePlaceholders()
    var requestId: Option[Int] = None
    // Form and send requests
    var householdVehiclesWereNotAvailable = false // to replan when personal vehicles are not available

    // Check if we should always query certain modes for skimming
    val shouldAlwaysQueryTransit = beamScenario.beamConfig.beam.exchange.output.generateSkimsForAllModes
    val shouldAlwaysQueryRideHailTransit =
      shouldAlwaysQueryTransit & beamScenario.beamConfig.beam.exchange.output.generateSkimsForRideHailTransit

    val mostRecentFailedTrip = choosesModeData.personData.failedTrips.lastOption
    val failedTransitLeg = mostRecentFailedTrip.flatMap(_.legs.find(_.beamLeg.mode.isTransit))

    val bufferToUse = failedTransitLeg match {
      case Some(transitLeg) =>
        // Get the departure time of the failed transit leg
        val failedTransitDepartureTime = transitLeg.beamLeg.startTime
        // Buffer to skip just past this transit departure
        (failedTransitDepartureTime - _currentTick.get) + BUFFER_PER_REPLANNING_ATTEMPT_IN_SEC

      case None =>
        // Fallback to standard buffer if no failed transit leg
        choosesModeData.personData.numberOfReplanningAttempts * BUFFER_PER_REPLANNING_ATTEMPT_IN_SEC
    }

    // Track ride hail requests that have already been made
    val (alreadyRequestedRideHail, alreadyRequestedRideHailTransit) =
      if (shouldAlwaysQueryTransit) {
        currentTripMode match {
          case Some(RIDE_HAIL | RIDE_HAIL_POOLED) if choosesModeData.isWithinTripReplanning => (false, false)
          case Some(RIDE_HAIL_TRANSIT) if choosesModeData.isWithinTripReplanning =>
            makeRideHailRequest()
            (true, false)
          case _ if hasRideHail =>
            makeRideHailRequest()
            if (shouldAlwaysQueryRideHailTransit) {
              requestId = makeRideHailTransitRoutingRequest(bodyStreetVehicle)
              (true, true)
            } else {
              (true, false)
            }
          case _ => (false, false)
        }
      } else { (false, false) }

    currentTripMode match {
      case None =>
        if (hasRideHail) {
          responsePlaceholders = makeResponsePlaceholders(
            withRouting = true,
            withRideHail = true,
            withRideHailTransit = !choosesModeData.isWithinTripReplanning
          )
          if (!alreadyRequestedRideHail) { makeRideHailRequest() }
          if (!choosesModeData.isWithinTripReplanning & !alreadyRequestedRideHailTransit) {
            requestId = makeRideHailTransitRoutingRequest(bodyStreetVehicle)
          }
        } else {
          responsePlaceholders = makeResponsePlaceholders(withRouting = true)
          requestId = None
        }

        // If you dont have mode pre-chosen, you can only use personal vehicles on vehicle based tours -- if you're
        // on a walk based tour, you can use shared vehicles all the time and personal vehicles for access/egress
        val availableStreetVehiclesGivenTourMode = availableVehicles.flatMap { vehicleOrToken =>
          val isPersonalVehicle = {
            !vehicleOrToken.vehicle.isSharedVehicle &&
            !BeamVehicle.isSharedTeleportationVehicle(vehicleOrToken.vehicle.id) &&
            !vehicleOrToken.vehicle.isRideHail
          }

          currentTourMode match {
            case Some(BIKE_BASED) if isPersonalVehicle =>
              Some(vehicleOrToken.streetVehicle)
            case Some(CAR_BASED) if isPersonalVehicle =>
              Some(vehicleOrToken.streetVehicle)
            case Some(WALK_BASED) if vehicleOrToken.vehicle.isSharedVehicle =>
              Some(vehicleOrToken.streetVehicle)
            case Some(WALK_BASED) if isPersonalVehicle && isFirstOrLastTripWithinTour(nextAct) =>
              Some(vehicleOrToken.streetVehicle)
            case None => Some(vehicleOrToken.streetVehicle)
            case _    => None
          }
        } :+ bodyStreetVehicle

        makeRequestWith(
          withTransit = availableModesGivenTourMode.exists(_.isTransit) || shouldAlwaysQueryTransit,
          availableStreetVehiclesGivenTourMode,
          possibleEgressVehicles = dummySharedVehicles,
          departureBuffer = bufferToUse
        )
      case Some(WALK) =>
        responsePlaceholders = makeResponsePlaceholders(
          withRouting = true,
          withRideHail = alreadyRequestedRideHail,
          withRideHailTransit = alreadyRequestedRideHailTransit
        )
        makeRequestWith(withTransit = shouldAlwaysQueryTransit, Vector(bodyStreetVehicle))
      case Some(WALK_TRANSIT) =>
        responsePlaceholders = makeResponsePlaceholders(
          withRouting = true,
          withRideHail = alreadyRequestedRideHail,
          withRideHailTransit = alreadyRequestedRideHailTransit
        )
        makeRequestWith(
          withTransit = true,
          Vector(bodyStreetVehicle),
          departureBuffer = bufferToUse
        )
      case Some(CAV) =>
        // Request from household the trip legs to put into trip
        householdRef ! CavTripLegsRequest(bodyVehiclePersonId, currentActivity(choosesModeData.personData))
        responsePlaceholders = makeResponsePlaceholders(
          withPrivateCAV = true,
          withRideHail = alreadyRequestedRideHail,
          withRideHailTransit = alreadyRequestedRideHailTransit
        )
      case Some(HOV2_TELEPORTATION) =>
        val vehicles = availableVehicles
          .filter(v => BeamVehicle.isSharedTeleportationVehicle(v.id))
          .map(car_vehicle => car_vehicle.streetVehicle.copy(mode = CAR_HOV2))
        makeRequestWith(withTransit = shouldAlwaysQueryTransit, vehicles :+ bodyStreetVehicle)
        responsePlaceholders = makeResponsePlaceholders(
          withRouting = true,
          withRideHail = alreadyRequestedRideHail,
          withRideHailTransit = alreadyRequestedRideHailTransit
        )
      case Some(HOV3_TELEPORTATION) =>
        val vehicles = availableVehicles
          .filter(v => BeamVehicle.isSharedTeleportationVehicle(v.id))
          .map(car_vehicle => car_vehicle.streetVehicle.copy(mode = CAR_HOV3))
        makeRequestWith(withTransit = shouldAlwaysQueryTransit, vehicles :+ bodyStreetVehicle)
        responsePlaceholders = makeResponsePlaceholders(
          withRouting = true,
          withRideHail = alreadyRequestedRideHail,
          withRideHailTransit = alreadyRequestedRideHailTransit
        )
      case Some(tripMode @ (CAR | BIKE | CAR_HOV2 | CAR_HOV3)) =>
        val maybeLeg = _experiencedBeamPlan.getPlanElements
          .get(_experiencedBeamPlan.getPlanElements.indexOf(nextAct) - 1) match {
          case l: Leg => Some(l)
          case _      => None
        }
        maybeLeg.map(_.getRoute) match {
          case Some(networkRoute: NetworkRoute) =>
            val maybeVehicle =
              filterStreetVehiclesForQuery(availableVehicles.map(_.streetVehicle), tripMode).headOption
            maybeVehicle match {
              case Some(vehicle) if vehicle.mode == tripMode =>
                router ! matsimLegToEmbodyRequest(
                  networkRoute,
                  vehicle,
                  departTime,
                  tripMode,
                  beamServices,
                  choosesModeData.currentLocation.loc,
                  nextAct.getCoord,
                  triggerId
                )
                responsePlaceholders = makeResponsePlaceholders(
                  withRouting = true,
                  withRideHail = alreadyRequestedRideHail,
                  withRideHailTransit = alreadyRequestedRideHailTransit
                )
              case Some(vehicle) =>
                logger.error(s"Agent ${this.id} is on a ${tripMode.value} trip but has vehicle ${vehicle.toString}")
                makeRequestWith(withTransit = shouldAlwaysQueryTransit, Vector(bodyStreetVehicle))
                responsePlaceholders = makeResponsePlaceholders(
                  withRouting = true,
                  withRideHail = alreadyRequestedRideHail,
                  withRideHailTransit = alreadyRequestedRideHailTransit
                )
              case _ =>
                makeRequestWith(withTransit = shouldAlwaysQueryTransit, Vector(bodyStreetVehicle))
                responsePlaceholders = makeResponsePlaceholders(
                  withRouting = true,
                  withRideHail = alreadyRequestedRideHail,
                  withRideHailTransit = alreadyRequestedRideHailTransit
                )
                logger.error(
                  "No vehicle available for existing route of person {} trip of mode {} even though it was created in their plans",
                  this.id,
                  tripMode
                )
            }
          case _ =>
            val vehicles = filterStreetVehiclesForQuery(availableVehicles.map(_.streetVehicle), tripMode)
              .map(vehicle => {
                vehicle.mode match {
                  case CAR => vehicle.copy(mode = tripMode)
                  case _   => vehicle
                }
              })
            if (
              beamScenario.beamConfig.beam.agentsim.agents.vehicles.replanOnTheFlyWhenHouseholdVehiclesAreNotAvailable && vehicles.isEmpty
            ) {
              val currentCoordWgs = beamServices.geo.utm2Wgs(currentPersonLocation.loc)
              eventsManager.processEvent(
                new ReplanningEvent(
                  departTime,
                  Id.createPersonId(id),
                  getReplanningReasonFrom(
                    choosesModeData.personData,
                    ReservationErrorCode.HouseholdVehicleNotAvailable.entryName
                  ),
                  currentCoordWgs.getX,
                  currentCoordWgs.getY
                )
              )
              householdVehiclesWereNotAvailable = true
              logger.warn("No HH vehicle available so going back to replanning")
            }
            makeRequestWith(
              withTransit = householdVehiclesWereNotAvailable | shouldAlwaysQueryTransit,
              vehicles :+ bodyStreetVehicle
            )
            responsePlaceholders = makeResponsePlaceholders(
              withRouting = true,
              withRideHail = householdVehiclesWereNotAvailable | alreadyRequestedRideHail,
              withRideHailTransit = householdVehiclesWereNotAvailable | alreadyRequestedRideHailTransit
            )
            if (householdVehiclesWereNotAvailable & !alreadyRequestedRideHail) {
              makeRideHailRequest()
              if (!choosesModeData.isWithinTripReplanning & !alreadyRequestedRideHailTransit) {
                requestId = makeRideHailTransitRoutingRequest(bodyStreetVehicle)
              }
            }
        }
      case Some(mode @ (DRIVE_TRANSIT | BIKE_TRANSIT)) =>
        val vehicleMode = Modes.getAccessVehicleMode(mode)
        val (tripIndexOfElement: Int, lastTripIndex: Int) = currentTripIndexWithinTour(nextAct)
        (
          tripIndexOfElement,
          choosesModeData.personData.currentTourPersonalVehicle
        ) match {
          case (0, _) =>
            if (!choosesModeData.isWithinTripReplanning) {
              // We use our car if we are not replanning, otherwise we end up doing a walk transit (catch-all below)
              // we do not send parking inquiry here, instead we wait for drive_transit route to come back and we use
              // actual location of transit station
              makeRequestWith(
                withTransit = true,
                filterStreetVehiclesForQuery(availableVehicles.map(_.streetVehicle), vehicleMode)
                :+ bodyStreetVehicle,
                departureBuffer = bufferToUse
              )
              responsePlaceholders = makeResponsePlaceholders(
                withRouting = true,
                withRideHail = alreadyRequestedRideHail,
                withRideHailTransit = alreadyRequestedRideHailTransit
              )
            } else {
              // Reset available vehicles so we don't release our car that we've left during this replanning
              resetVehicles = true
              makeRequestWith(
                withTransit = true,
                Vector(bodyStreetVehicle),
                departureBuffer = bufferToUse
              )
              responsePlaceholders = makeResponsePlaceholders(
                withRouting = true,
                withRideHail = alreadyRequestedRideHail,
                withRideHailTransit = alreadyRequestedRideHailTransit
              )
            }
          case (`lastTripIndex`, Some(currentTourPersonalVehicle)) =>
            val vehiclesForRouting = availableVehicles
              .map(_.streetVehicle)
              .filter(_.id == currentTourPersonalVehicle)
            val intermodalUse: IntermodalUse = if (vehiclesForRouting.isEmpty) {
              logger.error(
                s"Agent ${this.id} has tour vehicle ${currentTourPersonalVehicle.toString} in PersonData but " +
                s"has no available vehicles for routing on egress leg of drive transit trip"
              )
              AccessAndOrEgress
            } else {
              Egress
            }
            // At the end of the tour, only drive home a vehicle that we have also taken away from there.
            makeRequestWith(
              withTransit = true,
              vehiclesForRouting :+ bodyStreetVehicle,
              streetVehiclesIntermodalUse = intermodalUse,
              departureBuffer = bufferToUse
            )
            responsePlaceholders = makeResponsePlaceholders(
              withRouting = true,
              withRideHail = alreadyRequestedRideHail,
              withRideHailTransit = alreadyRequestedRideHailTransit
            )
          case _ =>
            // Reset available vehicles so we don't release our car that we've left during this replanning
            resetVehicles = true
            makeRequestWith(withTransit = true, Vector(bodyStreetVehicle))
            responsePlaceholders = makeResponsePlaceholders(
              withRouting = true,
              withRideHail = alreadyRequestedRideHail,
              withRideHailTransit = alreadyRequestedRideHailTransit
            )
        }
      case Some(RIDE_HAIL | RIDE_HAIL_POOLED) if choosesModeData.isWithinTripReplanning =>
        // Give up on all ride hail after a failure
        responsePlaceholders = makeResponsePlaceholders(withRouting = true)
        makeRequestWith(withTransit = true, Vector(bodyStreetVehicle))
      case Some(RIDE_HAIL | RIDE_HAIL_POOLED) =>
        responsePlaceholders = makeResponsePlaceholders(
          withRouting = true,
          withRideHail = true,
          withRideHailTransit = alreadyRequestedRideHailTransit
        )
        makeRequestWith(
          withTransit = shouldAlwaysQueryTransit,
          Vector(bodyStreetVehicle)
        ) // We need a WALK alternative if RH fails
        if (!alreadyRequestedRideHail) { makeRideHailRequest() }
      case Some(RIDE_HAIL_TRANSIT) if choosesModeData.isWithinTripReplanning =>
        // Give up on ride hail transit after a failure, too complicated, but try regular ride hail again
        responsePlaceholders = makeResponsePlaceholders(withRouting = true, withRideHail = true)
        makeRequestWith(withTransit = true, Vector(bodyStreetVehicle))
        if (!alreadyRequestedRideHail) { makeRideHailRequest() }
      case Some(RIDE_HAIL_TRANSIT) =>
        responsePlaceholders =
          makeResponsePlaceholders(withRideHailTransit = true, withRideHail = alreadyRequestedRideHail)
        if (!alreadyRequestedRideHailTransit) { requestId = makeRideHailTransitRoutingRequest(bodyStreetVehicle) }
      case Some(m) =>
        logDebug(m.toString)
    }

    (
      responsePlaceholders,
      requestId,
      if (resetVehicles) { Vector.empty[VehicleOrToken] }
      else availableVehicles
    )
  }

  /**
    * Determines the tour mode and assigns vehicles for a trip or tour based on the given strategy, available modes,
    * vehicles, and first leg itineraries.
    *
    * @param currentTourStrategy The current strategy for selecting the tour mode.
    * @param currentTripMode     The current mode of the trip, if already determined.
    * @param availableVehicles   A list of vehicles or tokens available for the person.
    * @param choosesModeData     The data used for mode choice decisions, containing person-related information.
    * @param firstLegItineraries A collection of potential itineraries for the first leg of the trip.
    * @return A tuple where the first element is the chosen tour mode (if any), and the second element is a mapping between
    *         embodied beam trips and the chosen vehicle IDs (if any).
    */
  private def chooseTourModeAndVehicle(
    currentTourStrategy: TourModeChoiceStrategy,
    currentTripMode: Option[BeamMode],
    availableVehicles: Vector[VehicleOrToken],
    choosesModeData: ChoosesModeData,
    firstLegItineraries: Vector[EmbodiedBeamTrip]
  ): (Option[BeamTourMode], Map[EmbodiedBeamTrip, Option[Id[BeamVehicle]]]) = {
    val availableModes: Seq[BeamMode] = availableModesForPerson(matsimPlan.getPerson, choosesModeData.excludeModes)
    val nextAct = nextActivity(choosesModeData.personData).get
    val departTime = _currentTick.get
    currentTourStrategy.tourMode match {
      case Some(tourMode) =>
        (
          Some(tourMode),
          firstLegItineraries.collect {
            case itin if currentTourStrategy.tourVehicle.exists(itin.vehiclesInTrip.contains) =>
              itin -> currentTourStrategy.tourVehicle
            case itin if currentTourStrategy.tourVehicle.isEmpty && tourMode.isVehicleBased =>
              if (tourMode != FREIGHT_TOUR) {
                logger.warn("Vehicle based tour mode without vehicle defined")
              }
              itin -> itin.legs.find(l => l.asDriver && (l.beamLeg.mode != WALK)).map(_.beamVehicleId)
          }.toMap
        )
      case None =>
        currentTripMode match {
          case None =>
            val availablePersonalVehicleModes =
              availableVehicles.map(x => x.streetVehicle.mode).distinct
            val availableFirstAndLastLegModes =
              availablePersonalVehicleModes.flatMap(x => BeamTourMode.enabledModes.get(x)).flatten
            val modesToQuery =
              (availablePersonalVehicleModes ++ BeamMode.nonPersonalVehicleModes ++ availableFirstAndLastLegModes).distinct
                .intersect(availableModes)
            val dummyVehicleType = beamScenario.vehicleTypes(dummyRHVehicle.vehicleTypeId)
            val currentTour = _experiencedBeamPlan.getTourContaining(nextAct)
            val tourModeCosts = beamServices.skims.od_skimmer.getTourModeCosts(
              modesToQuery,
              currentTour,
              dummyRHVehicle.vehicleTypeId,
              dummyVehicleType,
              beamScenario.fuelTypePrices(dummyVehicleType.primaryFuelType),
              Some(firstLegItineraries)
            )
            val modeToTourMode =
              BeamTourMode.values
                .map(tourMode =>
                  tourMode -> tourMode
                    .allowedBeamModesGivenAvailableVehicles(availableVehicles, firstOrLastLeg = false)
                    .intersect(modesToQuery)
                )
                .toMap
            val firstAndLastTripModeToTourMode = BeamTourMode.values
              .map(tourMode =>
                tourMode -> tourMode
                  .allowedBeamModesGivenAvailableVehicles(availableVehicles, firstOrLastLeg = true)
                  .intersect(modesToQuery)
              )
              .toMap
            val tourModeUtils = tourModeChoiceCalculator.tourExpectedMaxUtility(
              tourModeCosts,
              modeChoiceCalculator.modeChoiceLogit,
              modeToTourMode,
              Some(firstAndLastTripModeToTourMode)
            )
            val out = tourModeChoiceCalculator(tourModeUtils)

            // We need to keep track of the chosen vehicle Id in PersonData so that we can release it and
            // potentially give up on our tour mode choice if a route can't be constructed
            val chosenTourVehicle: Map[EmbodiedBeamTrip, Option[Id[BeamVehicle]]] = out match {
              case Some(tourMode) =>
                firstLegItineraries
                  .collect {
                    case itin
                        if tourMode
                          .allowedBeamModesGivenAvailableVehicles(availableVehicles, firstOrLastLeg = true)
                          .contains(itin.tripClassifier) =>
                      itin.vehiclesInTrip
                        .find(availableVehicles.map(_.id).contains)
                        .map(vid => itin -> Some(vid))
                  }
                  .flatten
                  .toMap
              case _ => Map.empty[EmbodiedBeamTrip, Option[Id[BeamVehicle]]]
            }

            val tourModeChoiceEvent = new TourModeChoiceEvent(
              departTime.toDouble,
              this.id,
              out.map(_.value).getOrElse(""),
              currentTour,
              availableVehicles,
              firstAndLastTripModeToTourMode,
              tourModeUtils,
              modesToQuery,
              currentActivity(choosesModeData.personData)
            )
            eventsManager.processEvent(tourModeChoiceEvent)
            (out, chosenTourVehicle)
          case Some(tripMode) =>
            // If trip mode is already set, determine tour mode from that and available vehicles (sticking
            // with walk based tour if the only available vehicles are shared)
            val chosenTourModeAndVehicle =
              getTourModeAndVehicle(
                firstLegItineraries.filter(_.tripClassifier == tripMode),
                availableVehicles,
                choosesModeData.personData.currentTourPersonalVehicle
              )
            val (chosenTourMode, chosenTourVehicleMap) = chosenTourModeAndVehicle.headOption match {
              case Some((tourMode, mapping)) => (tourMode, mapping)
              case _                         => (None, Map.empty[EmbodiedBeamTrip, Option[BeamVehicle]])
            }

            val tourModeChoiceEvent = new TourModeChoiceEvent(
              departTime.toDouble,
              this.id,
              chosenTourMode.map(_.value).getOrElse(""),
              _experiencedBeamPlan.getTourContaining(nextAct),
              availableVehicles,
              Map.empty[BeamTourMode, Seq[BeamMode]],
              Map.empty[BeamTourMode, Double],
              Vector(tripMode),
              currentActivity(choosesModeData.personData)
            )
            eventsManager.processEvent(tourModeChoiceEvent)
            (
              chosenTourMode,
              chosenTourVehicleMap.collect { case (trip, veh) => trip -> veh.map(_.id) }.toMap
            )
        }
    }
  }

  private def updateTourModeStrategy(
    newTourMode: Option[BeamTourMode],
    newTourVehicle: Option[Id[BeamVehicle]],
    nextActivity: Activity,
    vehicles: Vector[VehicleOrToken]
  ): TourModeChoiceStrategy = {
    (newTourMode, newTourVehicle) match {
      case (Some(CAR_BASED), None) =>
        logger.error("Why are we going into a car based tour without a car?")
      case _ =>
    }
    val currentTour = _experiencedBeamPlan.getTourContaining(nextActivity)
    val legStrategies =
      currentTour.trips.flatMap(_.leg).map(leg => _experiencedBeamPlan.getStrategy[TripModeChoiceStrategy](leg))

    val mismatchedLegStrategies = newTourMode match {
      case Some(tourMode) =>
        legStrategies.zipWithIndex.filter { case (legStrategy, index) =>
          val isFirstOrLastTrip = index == 0 || index == legStrategies.size - 1
          legStrategy.mode match {
            case Some(maybeTripMode) =>
              !tourMode.allowedBeamModesGivenAvailableVehicles(vehicles, isFirstOrLastTrip).contains(maybeTripMode)
            case _ => false
          }
        }
      case _ => Seq()
    }

    mismatchedLegStrategies.foreach { case (st, idx) =>
      _experiencedBeamPlan.putStrategy(currentTour.trips.apply(idx), TripModeChoiceStrategy(None))
      logger.debug(
        f"Replacing person ${this.id}'s planned ${st.mode.get} trip with none because of conflict with tour mode ${newTourMode.get}"
      )
    }

    val updatedTourStrategy =
      TourModeChoiceStrategy(
        newTourMode,
        newTourVehicle
      )
    _experiencedBeamPlan.putStrategy(currentTour, updatedTourStrategy)
    updatedTourStrategy
  }
}

object ChoosesMode {

  case class TripIdentifier(tripClassifier: BeamMode, legModes: IndexedSeq[BeamMode]) {

    def isAppropriateTrip(trip: EmbodiedBeamTrip): Boolean =
      trip.tripClassifier == tripClassifier &&
      TripIdentifier.filterMainVehicles(trip).map(_.beamLeg.mode) == legModes
  }

  private object TripIdentifier {

    def apply(trip: EmbodiedBeamTrip): TripIdentifier = {
      val filteredLegs = filterMainVehicles(trip)
      TripIdentifier(trip.tripClassifier, filteredLegs.map(_.beamLeg.mode))
    }

    private def filterMainVehicles(trip: EmbodiedBeamTrip): IndexedSeq[EmbodiedBeamLeg] = {
      val (filtered, last) = trip.legs.tail.foldLeft(IndexedSeq.empty[EmbodiedBeamLeg] -> trip.legs.head) {
        case ((accum, prevLeg), leg) =>
          if (prevLeg.beamLeg.mode != BeamMode.WALK && prevLeg.beamVehicleId != leg.beamVehicleId)
            (accum :+ prevLeg) -> leg
          else
            accum -> leg
      }
      if (last.beamLeg.mode != BeamMode.WALK)
        filtered :+ last
      else
        filtered
    }
  }

  case class VehicleOnTrip(vehicleId: Id[BeamVehicle], tripIdentifier: TripIdentifier)

  case class ChoosesModeData(
    personData: BasePersonData,
    currentLocation: SpaceTime,
    pendingChosenTrip: Option[EmbodiedBeamTrip] = None,
    routingResponse: Option[RoutingResponse] = None,
    parkingResponses: Map[VehicleOnTrip, ParkingInquiryResponse] = Map.empty,
    parkingRequestIds: Map[Int, VehicleOnTrip] = Map.empty,
    rideHailResult: Option[RideHailResponse] = None,
    rideHail2TransitRoutingResponse: Option[EmbodiedBeamTrip] = None,
    rideHail2TransitRoutingRequestId: Option[Int] = None,
    rideHail2TransitAccessResult: Option[RideHailResponse] = None,
    rideHail2TransitAccessInquiryId: Option[Int] = None,
    rideHail2TransitEgressResult: Option[RideHailResponse] = None,
    rideHail2TransitEgressInquiryId: Option[Int] = None,
    availablePersonalStreetVehicles: Vector[VehicleOrToken] = Vector(),
    allAvailableStreetVehicles: Vector[VehicleOrToken] = Vector(),
    expectedMaxUtilityOfLatestChoice: Option[Double] = None,
    isWithinTripReplanning: Boolean = false,
    cavTripLegs: Option[CavTripLegsResponse] = None,
    excludeModes: Set[BeamMode] = Set.empty[BeamMode],
    availableAlternatives: Option[String] = None,
    routingFinished: Boolean = false,
    routingRequestToLegMap: Map[Int, TripIdentifier] = Map.empty
  ) extends PersonData {
    override def currentVehicle: VehicleStack = personData.currentVehicle

    override def currentLegPassengerScheduleIndex: Int =
      personData.currentLegPassengerScheduleIndex

    override def passengerSchedule: PassengerSchedule =
      personData.passengerSchedule

    override def withPassengerSchedule(newPassengerSchedule: PassengerSchedule): DrivingData =
      copy(personData = personData.copy(passengerSchedule = newPassengerSchedule))

    override def withCurrentLegPassengerScheduleIndex(
      currentLegPassengerScheduleIndex: Int
    ): DrivingData =
      copy(personData = personData.copy(currentLegPassengerScheduleIndex = currentLegPassengerScheduleIndex))

    override def hasParkingBehaviors: Boolean = true

    override def geofence: Option[Geofence] = None

    override def legStartsAt: Option[Int] = None

  }

  private case class MobilityStatusWithLegs(
    responses: Seq[(EmbodiedBeamTrip, EmbodiedBeamLeg, MobilityStatusResponse)]
  )

  private case class ChoosesModeResponsePlaceholders(
    routingResponse: Option[RoutingResponse] = None,
    rideHailResult: Option[RideHailResponse] = None,
    rideHail2TransitRoutingResponse: Option[EmbodiedBeamTrip] = None,
    rideHail2TransitAccessResult: Option[RideHailResponse] = None,
    rideHail2TransitEgressResult: Option[RideHailResponse] = None,
    cavTripLegs: Option[CavTripLegsResponse] = None
  )

  private def makeResponsePlaceholders(
    withRouting: Boolean = false,
    withRideHail: Boolean = false,
    withRideHailTransit: Boolean = false,
    withPrivateCAV: Boolean = false
  ): ChoosesModeResponsePlaceholders = {
    ChoosesModeResponsePlaceholders(
      routingResponse = if (withRouting) {
        None
      } else {
        RoutingResponse.dummyRoutingResponse
      },
      rideHailResult = if (withRideHail) {
        None
      } else {
        Some(RideHailResponse.dummyWithError(RideHailNotRequestedError))
      },
      rideHail2TransitRoutingResponse = if (withRideHailTransit) {
        None
      } else {
        Some(EmbodiedBeamTrip.empty)
      },
      rideHail2TransitAccessResult = if (withRideHailTransit) {
        None
      } else {
        Some(RideHailResponse.dummyWithError(RideHailNotRequestedError))
      },
      rideHail2TransitEgressResult = if (withRideHailTransit) {
        None
      } else {
        Some(RideHailResponse.dummyWithError(RideHailNotRequestedError))
      },
      cavTripLegs = if (withPrivateCAV) {
        None
      } else {
        Some(CavTripLegsResponse(None, List()))
      }
    )
  }

  case class CavTripLegsRequest(person: PersonIdWithActorRef, originActivity: Activity)

  case class CavTripLegsResponse(cavOpt: Option[BeamVehicle], legs: List[EmbodiedBeamLeg])

  private def getActivityEndTime(activity: Activity, beamServices: BeamServices): Int = {
    activity.getEndTime.orElseGet(() => Time.parseTime(beamServices.beamConfig.matsim.modules.qsim.endTime)).toInt
  }
}
