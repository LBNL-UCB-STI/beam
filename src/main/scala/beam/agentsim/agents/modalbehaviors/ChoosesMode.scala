package beam.agentsim.agents.modalbehaviors

import akka.actor.{ActorRef, FSM}
import akka.pattern.pipe
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents._
import beam.agentsim.agents.household.HouseholdActor.{MobilityStatusInquiry, MobilityStatusResponse, ReleaseVehicle}
import beam.agentsim.agents.modalbehaviors.ChoosesMode._
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.{ActualVehicle, Token, VehicleOrToken}
import beam.agentsim.agents.planning.Strategy.{TourModeChoiceStrategy, TripModeChoiceStrategy}
import beam.agentsim.agents.ridehail.{RideHailInquiry, RideHailManager, RideHailRequest, RideHailResponse}
import beam.agentsim.agents.vehicles.AccessErrorCodes.RideHailNotRequestedError
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{BeamVehicle, _}
import beam.agentsim.events.resources.ReservationErrorCode
import beam.agentsim.events.{ModeChoiceEvent, ReplanningEvent, SpaceTime, TourModeChoiceEvent}
import beam.agentsim.infrastructure.{ParkingInquiry, ParkingInquiryResponse, ZonalParkingManager}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
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
import beam.utils.MathUtils._
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

  private val dummyRHVehicle: StreetVehicle = createDummyVehicle(
    "dummyRH",
    beamServices.beamConfig.beam.agentsim.agents.rideHail.managers.head.initialization.procedural.vehicleTypeId,
    CAR,
    asDriver = false
  )

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

    (nextStateData, currentTripMode, currentTourMode) match {
      // If I am already on a tour in a vehicle, only that vehicle is available to me
      // Unless it's a walk based tour and I used that vehicle for egress on my first trip
      case (data: ChoosesModeData, _, tourMode @ Some(CAR_BASED | BIKE_BASED | FREIGHT_TOUR)) =>
        if (data.personData.currentTourPersonalVehicle.isDefined) {
          val currentTourVehicle = Vector(beamVehicles(data.personData.currentTourPersonalVehicle.get))
          self ! MobilityStatusResponse(
            currentTourVehicle,
            getCurrentTriggerIdOrGenerate
          )
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
        self ! MobilityStatusResponse(
          Vector(beamVehicles(data.personData.currentTourPersonalVehicle.get)),
          getCurrentTriggerIdOrGenerate
        )
      // Create teleportation vehicle if we are told to use teleportation
      case (data: ChoosesModeData, Some(HOV2_TELEPORTATION | HOV3_TELEPORTATION), _) =>
        val teleportationVehicle = createSharedTeleportationVehicle(data.currentLocation)
        val vehicles = Vector(ActualVehicle(teleportationVehicle))
        self ! MobilityStatusResponse(vehicles, getCurrentTriggerIdOrGenerate)
      // Only need to get available street vehicles if our mode requires such a vehicle
      case (data: ChoosesModeData, Some(CAR | CAR_HOV2 | CAR_HOV3 | DRIVE_TRANSIT), _) =>
        implicit val executionContext: ExecutionContext = context.system.dispatcher
        requestAvailableVehicles(
          vehicleFleets,
          data.currentLocation,
          currentActivity(data.personData),
          Some(VehicleCategory.Car)
        ) pipeTo self
      case (data: ChoosesModeData, Some(BIKE | BIKE_TRANSIT), _) =>
        implicit val executionContext: ExecutionContext = context.system.dispatcher
        requestAvailableVehicles(
          vehicleFleets,
          data.currentLocation,
          currentActivity(data.personData),
          Some(VehicleCategory.Bike)
        ) pipeTo self
      // If we're on a walk based tour and have an egress vehicle defined we NEED to bring it home

      case (_, None, Some(WALK_BASED)) if currentTourStrategy.tourVehicle.isDefined && isLastTripWithinTour(nextAct) =>
        self ! MobilityStatusResponse(
          Vector(beamVehicles(currentTourStrategy.tourVehicle.get)),
          getCurrentTriggerIdOrGenerate
        )
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

      val availableVehicleFromParentTour = currentTourStrategy.tourMode match {
        case Some(_) => Vector()
        case None =>
          personData.currentTourPersonalVehicle.map(vehId => beamVehicles(vehId)).toVector
      }
      availablePersonalStreetVehicles ++= availableVehicleFromParentTour

      val availableModesGivenTourMode = getAvailableModesGivenTourMode(
        availableModes,
        availablePersonalStreetVehicles,
        currentTourStrategy.tourMode,
        nextAct,
        personData.currentTourPersonalVehicle
      )

      if (availableModesGivenTourMode.length == 1) {
        logger.debug(
          "Only one option (${availableModesGivenTourMode.head.value}) available so let's save some effort "
          + "and only query routes for that mode"
        )
        currentTripMode = availableModesGivenTourMode.headOption
      }

      val availableEmergencyVehicles =
        beamVehicles.filterKeys(k => k.toString.startsWith(f"${this.id.toString}-emergency")).values.toVector

      val hasRideHail = availableModesGivenTourMode.contains(RIDE_HAIL)
      val (responsePlaceholders, requestId, remainingAvailableVehicles) = makeRoutingRequests(
        currentTripMode,
        currentTourStrategy.tourMode,
        hasRideHail,
        availablePersonalStreetVehicles ++ availableEmergencyVehicles,
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
        availablePersonalStreetVehicles = availablePersonalStreetVehicles,
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

      val rhTransitTrip = theRouterResult.itineraries.find(_.tripClassifier == RIDE_HAIL_TRANSIT)
      // If there's a drive-transit trip AND we don't have an error RH2Tr response (due to no desire to use RH) then seek RH on access and egress
      val newPersonData =
        if (
          shouldAttemptRideHail2Transit(
            rhTransitTrip,
            choosesModeData.rideHail2TransitAccessResult
          )
        ) {
          val accessSegment =
            rhTransitTrip.get.legs.view
              .takeWhile(!_.beamLeg.mode.isMassTransit)
              .map(_.beamLeg)
          val egressSegment =
            rhTransitTrip.get.legs.view.reverse.takeWhile(!_.beamLeg.mode.isTransit).reverse.map(_.beamLeg)
          val accessId =
            if (accessSegment.map(_.travelPath.distanceInM).sum > 0) {
              makeRideHailRequestFromBeamLeg(accessSegment)
            } else {
              None
            }
          val egressId =
            if (egressSegment.map(_.travelPath.distanceInM).sum > 0) {
              makeRideHailRequestFromBeamLeg(egressSegment.toVector)
            } else {
              None
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
            rideHail2TransitEgressResult = Some(RideHailResponse.dummyWithError(RideHailNotRequestedError))
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
          routingFinished = true
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
          choosesModeData.copy(rideHail2TransitAccessResult = Some(theRideHailResult))
        case choosesModeData.rideHail2TransitEgressInquiryId =>
          choosesModeData.copy(rideHail2TransitEgressResult = Some(theRideHailResult))
        case _ =>
          choosesModeData.copy(rideHailResult = Some(theRideHailResult))
      }
      stay() using newPersonData
    case Event(parkingInquiryResponse: ParkingInquiryResponse, choosesModeData: ChoosesModeData) =>
      val newPersonData = choosesModeData.copy(parkingResponses =
        choosesModeData.parkingResponses +
        (choosesModeData.parkingRequestIds(parkingInquiryResponse.requestId) -> parkingInquiryResponse)
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

  private def correctCurrentTripModeAccordingToRules(
    currentTripMode: Option[BeamMode],
    personData: BasePersonData,
    availableModes: Seq[BeamMode]
  ): Option[BeamMode] = {
    val replanningIsAvailable =
      personData.numberOfReplanningAttempts < beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.maximumNumberOfReplanningAttempts
    currentTripMode match {
      case Some(mode @ (HOV2_TELEPORTATION | HOV3_TELEPORTATION))
          if availableModes.contains(CAR) && replanningIsAvailable =>
        Some(mode)
      case Some(mode) if availableModes.contains(mode) && replanningIsAvailable => Some(mode)
      case Some(mode) if availableModes.contains(mode)                          => Some(WALK)
      case None if !replanningIsAvailable                                       => Some(WALK)
      case _                                                                    => None
    }
  }

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
    rideHail2TransitResult.getOrElse(RideHailResponse.DUMMY).error.isEmpty
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
    //    println(s"requesting: ${inquiry.requestId}")
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

  private def createRideHail2TransitItin(
    rideHail2TransitAccessResult: RideHailResponse,
    rideHail2TransitEgressResult: RideHailResponse,
    driveTransitTrip: EmbodiedBeamTrip
  ): Vector[EmbodiedBeamTrip] = {
    if (
      rideHail2TransitAccessResult.error.isEmpty &&
      rideHail2TransitEgressResult.error.forall(error => error == RideHailNotRequestedError)
    ) {
      val timeToCustomer = rideHail2TransitAccessResult.travelProposal.get.passengerSchedule
        .legsBeforePassengerBoards(bodyVehiclePersonId)
        .map(_.duration)
        .sum
      for {
        tncAccessLeg <- travelProposalToRideHailLegs(
          rideHail2TransitAccessResult.travelProposal.get,
          rideHail2TransitAccessResult.rideHailManagerName,
          None
        )
        tncEgressLeg <-
          if (rideHail2TransitEgressResult.error.isEmpty)
            travelProposalToRideHailLegs(
              rideHail2TransitEgressResult.travelProposal.get,
              rideHail2TransitEgressResult.rideHailManagerName,
              None
            )
          else Vector(Vector.empty)
        rhTransitTrip <- createRideHailTransitTrip(driveTransitTrip, tncAccessLeg, timeToCustomer, tncEgressLeg)
      } yield rhTransitTrip
    } else {
      Vector.empty
    }
  }

  private def createRideHailTransitTrip(
    driveTransitTrip: EmbodiedBeamTrip,
    tncAccessLeg: Vector[EmbodiedBeamLeg],
    timeToCustomer: Int,
    tncEgressLeg: Vector[EmbodiedBeamLeg]
  ) = {
    val accessLegDurationWithoutWaiting = tncAccessLeg.map(_.beamLeg.duration).sum
    val walkToRideHailStop = tncAccessLeg.find(_.is(WALK))
    val accessLegWaitingTime =
      walkToRideHailStop.fold(timeToCustomer)(leg => math.max(timeToCustomer - leg.beamLeg.duration, 0))
    // Replacing drive access leg with TNC changes the travel time.
    val extraWaitTimeBuffer = driveTransitTrip.legs.head.beamLeg.endTime - _currentTick.get -
      accessLegDurationWithoutWaiting - accessLegWaitingTime
    if (extraWaitTimeBuffer < 300) {
      // We filter out all options that don't allow at least 5 minutes of time for unexpected waiting
      None
    } else {
      // Travel time usually decreases, adjust for this but add a buffer to the wait time to account for uncertainty in actual wait time
      val startTimeAdjustment =
        driveTransitTrip.legs.head.beamLeg.endTime - accessLegDurationWithoutWaiting - accessLegWaitingTime
      val startTimeBufferForWaiting = clamp(timeToCustomer * 1.5, 300, extraWaitTimeBuffer)
      val accessTransitEgress = EmbodiedBeamLeg.makeLegsConsistent(
        tncAccessLeg,
        startTimeAdjustment - doubleToInt(startTimeBufferForWaiting)
      ) ++ driveTransitTrip.legs.tail
      val fullTrip = if (tncEgressLeg.nonEmpty) {
        val accessAndTransit = accessTransitEgress.dropRight(2)
        val egressHead = tncEgressLeg.head
        //make egress walk leg to start right after the person leaves the transit vehicle
        val egressLeg = if (egressHead.is(WALK)) {
          val walkAtTransitEnd =
            egressHead.copy(beamLeg = egressHead.beamLeg.updateStartTime(accessAndTransit.last.beamLeg.endTime))
          walkAtTransitEnd +: tncEgressLeg.tail
        } else
          tncEgressLeg
        accessAndTransit ++ egressLeg
      } else {
        accessTransitEgress.dropRight(1)
      }
      Some(surroundWithWalkLegsIfNeededAndMakeTrip(fullTrip))
    }
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
    * Checks to see what modes are allowed, given (1) What tour mode you are on, and (2) whether there are any tour
    * vehicles associated with your plan. 2 becomes important on WALK_BASED tours when you have used a vehicle for
    * initial access/egress
    */
  private def getAvailableModesGivenTourMode(
    availableModes: Seq[BeamMode],
    availablePersonalStreetVehicles: Vector[VehicleOrToken],
    currentTourMode: Option[BeamTourMode],
    nextActivity: Activity,
    maybeTourPersonalVehicle: Option[Id[BeamVehicle]] = None
  ): Seq[BeamMode] = {
    availableModes.intersect(currentTourMode match {
      case Some(WALK_BASED)
          if availablePersonalStreetVehicles
            .exists(_.vehicle.isMustBeDrivenHome) && isLastTripWithinTour(nextActivity) =>
        val requiredEgressModes = availablePersonalStreetVehicles.flatMap {
          case veh: ActualVehicle =>
            maybeTourPersonalVehicle match {
              case Some(tourVehicleId) if veh.id == tourVehicleId =>
                BeamTourMode.enabledModes.get(veh.streetVehicle.mode)
              case None if veh.vehicle.isMustBeDrivenHome =>
                logger.warn(
                  s"Person person ${this.id} is already on a walk based tour, and we have access to vehicle " +
                  s" ${veh.vehicle.id}, and we're" +
                  " on the way home, but it is not our tour personal vehicle. Going to drive it home."
                )
                BeamTourMode.enabledModes.get(veh.streetVehicle.mode)

              case Some(tourVehicleId) =>
                logger.warn(
                  s"Person person ${this.id} is on a walk tour with the wrong tour vehicle: $tourVehicleId when " +
                  s"we have access to ${veh.vehicle.id}"
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

  def mustBeDrivenHome(vehicle: VehicleOrToken): Boolean = {
    vehicle match {
      case ActualVehicle(beamVehicle) =>
        beamVehicle.isMustBeDrivenHome
      case _: Token =>
        false // is not a household vehicle
    }
  }

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
            _,
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
      val rideHail2TransitIinerary = createRideHail2TransitItin(
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

      val combinedItinerariesForChoice = rideHailItinerary ++ addParkingCostToItins(
        routingResponse.itineraries,
        parkingResponses
      ) ++ rideHail2TransitIinerary

      def isAvailable(mode: BeamMode): Boolean = combinedItinerariesForChoice.exists(_.tripClassifier == mode)

      choosesModeData.personData.currentTripMode match {
        case Some(expectedMode) if !isAvailable(expectedMode) =>
          eventsManager.processEvent(
            createFailedODSkimmerEvent(currentActivity(personData), nextAct, expectedMode)
          )
        case _ =>
      }

      if (currentTourStrategy.tourVehicle.exists(v => !beamVehicles.contains(v))) {
        logError(
          f"Why is our tour vehicle ${currentTourStrategy.tourVehicle} not in beamVehicles?"
        ) // happens if we create an emergency vehicle for a subtour -- probably shouldn't be allowed
      }

      val (chosenCurrentTourMode, chosenCurrentTourPersonalVehicle) =
        chooseTourModeAndVehicle(
          currentTourStrategy,
          choosesModeData.personData.currentTripMode,
          allAvailableStreetVehicles ++ currentTourStrategy.tourVehicle
            .flatMap(v => beamVehicles.get(v))
            .filterNot(_.vehicle.isSharedVehicle)
            .toVector,
          choosesModeData,
          Some(combinedItinerariesForChoice)
        )

      chosenCurrentTourMode match {
        case Some(CAR_BASED)
            if !allAvailableStreetVehicles
              .exists(_.vehicle.beamVehicleType.vehicleCategory == VehicleCategory.Car) =>
          logger.error("We're on a car based tour without any cars -- this is bad!")
        case Some(BIKE_BASED)
            if !allAvailableStreetVehicles
              .exists(_.vehicle.beamVehicleType.vehicleCategory == VehicleCategory.Bike) =>
          logger.error("We're on a bike based tour without any bikes -- this is bad!")
        case _ =>
      }

      val availableModesForTrips = getAvailableModesGivenTourMode(
        availableModesForPerson(matsimPlan.getPerson, choosesModeData.excludeModes),
        choosesModeData.availablePersonalStreetVehicles,
        chosenCurrentTourMode,
        nextAct,
        chosenCurrentTourPersonalVehicle
      )

      val filteredItinerariesForChoice = choosesModeData.personData.currentTripMode match {
        case Some(mode) if mode == DRIVE_TRANSIT || mode == BIKE_TRANSIT =>
          (isFirstOrLastTripWithinTour(nextAct), personData.hasDeparted) match {
            case (true, false) =>
              combinedItinerariesForChoice.filter(_.tripClassifier == mode)
            case _ =>
              combinedItinerariesForChoice.filter(trip =>
                trip.tripClassifier == WALK_TRANSIT || trip.tripClassifier == RIDE_HAIL_TRANSIT
              )
          }
        case Some(mode) if mode == WALK_TRANSIT || mode == RIDE_HAIL_TRANSIT =>
          combinedItinerariesForChoice.filter(trip =>
            trip.tripClassifier == WALK_TRANSIT || trip.tripClassifier == RIDE_HAIL_TRANSIT
          )
        case Some(HOV2_TELEPORTATION) =>
          combinedItinerariesForChoice.filter(_.tripClassifier == HOV2_TELEPORTATION)
        case Some(HOV3_TELEPORTATION) =>
          combinedItinerariesForChoice.filter(_.tripClassifier == HOV3_TELEPORTATION)
        case Some(mode) =>
          combinedItinerariesForChoice.filter(_.tripClassifier == mode)
        case _ =>
          combinedItinerariesForChoice
      }

      val itinerariesOfCorrectMode =
        filteredItinerariesForChoice
          .filter(itin => availableModesForTrips.contains(itin.tripClassifier))
          .filterNot(itin =>
            itin.vehiclesInTrip
              .filterNot(_.toString.startsWith("body"))
              .exists(veh => personData.failedTrips.flatMap(_.vehiclesInTrip).contains(veh))
          )

      val attributesOfIndividual =
        matsimPlan.getPerson.getCustomAttributes
          .get("beam-attributes")
          .asInstanceOf[AttributesOfIndividual]
      val availableAlts = Some(itinerariesOfCorrectMode.map(_.tripClassifier).mkString(":"))

      def gotoFinishingModeChoice(chosenTrip: EmbodiedBeamTrip) = {
        goto(FinishingModeChoice) using choosesModeData.copy(
          personData = personData.copy(
            currentTourMode = chosenCurrentTourMode,
            currentTourPersonalVehicle = chosenCurrentTourMode match {
              // if they're on a walk based tour we let them keep access to whatever personal vehicle they used on the
              // first leg or in a parent tour
              case Some(WALK_BASED) => choosesModeData.personData.currentTourPersonalVehicle
              // Otherwise they keep track of the chosen vehicle
              case _ => chosenCurrentTourPersonalVehicle
            }
          ),
          pendingChosenTrip = Some(chosenTrip),
          availableAlternatives = availableAlts
        )
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
          filteredItinerariesForChoice.foreach {
            case possibleTrip
                if (possibleTrip != chosenTrip) && beamScenario.beamConfig.beam.router.skim.sendNonChosenTripsToSkimmer =>
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
          val dataForNextStep =
            choosesModeData.copy(
              personData = personData.copy(currentTourMode = chosenCurrentTourMode),
              pendingChosenTrip = Some(chosenTrip),
              availableAlternatives = availableAlts
            )
          goto(FinishingModeChoice) using dataForNextStep
        case None =>
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
            case Some(CAR)
                if allAvailableStreetVehicles.isEmpty &&
                  beamScenario.beamConfig.beam.agentsim.agents.vehicles.generateEmergencyHouseholdVehicleWhenPlansRequireIt =>
              logger.warn(
                s"Person ${this.id} ended up stuck without a car despite having car in plans, so sending the request " +
                s"back through in order to create an emergency vehicle. Tick ${_currentTick.getOrElse(-1)} and " +
                s"activity ${_experiencedBeamPlan.getTripContaining(personData.currentActivityIndex)} " +
                s"of plan ${_experiencedBeamPlan.activities.map(_.getType)}"
              )
              goto(ChoosingMode)
            case Some(mode) =>
              val correctedTripMode = correctCurrentTripModeAccordingToRules(None, personData, availableModesForTrips)
              if (correctedTripMode != personData.currentTripMode) {
                val nextActLoc = nextActivity(choosesModeData.personData).get.getCoord
                val currentAct = currentActivity(personData)
                val odFailedSkimmerEvent = createFailedODSkimmerEvent(currentAct, nextAct, mode)
                val possibleActivitySimModes =
                  determineActivitySimPathTypesFromBeamMode(choosesModeData.personData.currentTripMode, currentAct)
                eventsManager.processEvent(
                  odFailedSkimmerEvent
                )
                if (beamServices.beamConfig.beam.exchange.output.activity_sim_skimmer.exists(_.primary.enabled)) {
                  createFailedActivitySimSkimmerEvent(currentAct, nextAct, possibleActivitySimModes).foreach(ev =>
                    eventsManager.processEvent(ev)
                  )
                }
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
                    nextActLoc.getX,
                    nextActLoc.getY
                  )
                ) //give another chance to make a choice without predefined mode
                //TODO: Do we need to do anything with tour mode here?
                gotoChoosingModeWithoutPredefinedMode(choosesModeData)
              } else {
                val expensiveWalkTrip = createExpensiveWalkTrip(currentPersonLocation, nextAct, routingResponse)
                gotoFinishingModeChoice(expensiveWalkTrip)
              }
            case _ =>
              // Bad things happen but we want them to continue their day, so we signal to downstream that trip should be made to be expensive
              val expensiveWalkTrip = createExpensiveWalkTrip(currentPersonLocation, nextAct, routingResponse)
              gotoFinishingModeChoice(expensiveWalkTrip)
          }
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
    val expensiveWalkTrip = EmbodiedBeamTrip(
      Vector(originalWalkTripLeg.copy(replanningPenalty = 10.0))
    )
    expensiveWalkTrip
  }

  private def gotoChoosingModeWithoutPredefinedMode(choosesModeData: ChoosesModeData) = {
    // TODO: Check modes for subsequent trips here
    val onFirstTrip = isFirstTripWithinTour(currentActivity(choosesModeData.personData))
    val outcomeTourMode = if (onFirstTrip) { None }
    else { Some(WALK_BASED) }
    val newTourVehicle = choosesModeData.personData.currentTourPersonalVehicle match {
      case Some(id) if beamVehicles.contains(id) =>
        if (choosesModeData.personData.currentTourMode.contains(WALK_BASED) & !onFirstTrip) {
          Some(id)
        } else {
          val vehicle = beamVehicles(id).vehicle
          vehicle.setMustBeDrivenHome(false)
          beamVehicles.remove(vehicle.id)
          vehicle.getManager.get ! ReleaseVehicle(vehicle, getCurrentTriggerId.get)
          if (!onFirstTrip) {
            logger.warn(
              s"Abandoning vehicle $id because no return ${choosesModeData.personData.currentTripMode} " +
              s"itinerary is available"
            )
          }
          None
        }
      case _ => None
    }

    if (choosesModeData.personData.currentTripMode.get.isTeleportation) {
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
    modes.map { pathType =>
      ActivitySimSkimmerFailedTripEvent(
        origin = origin,
        destination = destination,
        eventTime = _currentTick.get,
        activitySimPathType = pathType,
        iterationNumber = beamServices.matsimServices.getIterationNumber,
        skimName = beamServices.beamConfig.beam.router.skim.activity_sim_skimmer.name
      )
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
      val currentAct = currentActivity(data.personData)
      val correctedActivityEndTime = calculateActivityEndTime(currentAct, currentAct.getStartTime.orElse(tick.toDouble))
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

      val nextAct = nextActivity(data.personData).get

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
        _experiencedBeamPlan.tourIndexOfElement(nextAct),
        chosenTrip,
        _experiencedBeamPlan.activities(data.personData.currentActivityIndex).getType,
        nextAct.getType,
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

          val updatedTourStrategy =
            TourModeChoiceStrategy(data.personData.currentTourMode, data.personData.currentTourPersonalVehicle)
          val updatedTripStrategy =
            TripModeChoiceStrategy(Some(chosenTrip.tripClassifier))
          _experiencedBeamPlan.putStrategy(_experiencedBeamPlan.getTripContaining(nextAct), updatedTripStrategy)
          _experiencedBeamPlan.putStrategy(_experiencedBeamPlan.getTourContaining(nextAct), updatedTourStrategy)

          goto(Teleporting) using data.personData.copy(
            currentTrip = Some(chosenTrip),
            currentTourPersonalVehicle = None,
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
              if (
                data.personData.currentTourMode
                  .contains(WALK_BASED) && (!isFirstTripWithinTour(nextAct) || data.isWithinTripReplanning)
              ) {
                logger.debug(
                  s"We're keeping vehicle ${vehicle.id} even though it isn't used in this trip " +
                  s"because we need it for egress at the end of the tour"
                )
              } else {
                logError(
                  s"We are going to need to give up vehicle " +
                  s"${vehicle.id} because there isn't a route for it for our next leg - $data"
                )
                isCurrentPersonalVehicleVoided = true
                vehicle.setMustBeDrivenHome(false)
                beamVehicles.remove(vehicle.id)
                vehicle.getManager.get ! ReleaseVehicle(vehicle, triggerId)
              }
            case ActualVehicle(vehicle)
                if _experiencedBeamPlan
                  .getStrategy[TourModeChoiceStrategy](_experiencedBeamPlan.getTripContaining(currentAct))
                  .tourVehicle
                  .contains(vehicle.id) =>
              logError("Should we actually be keeping this vehicle")
            case ActualVehicle(vehicle)
                if _experiencedBeamPlan
                  .getStrategy[TourModeChoiceStrategy](_experiencedBeamPlan.getTripContaining(nextAct))
                  .tourVehicle
                  .contains(vehicle.id) =>
              logError("Should we actually be keeping this vehicle either?")
            case ActualVehicle(vehicle) =>
              beamVehicles.remove(vehicle.id)
              vehicle.getManager.get ! ReleaseVehicle(vehicle, triggerId)
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
              vehiclesUsed.headOption.filter(!_.vehicle.isSharedVehicle).map(_.id)
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

          val (finalTourMode, finalTourVehicle) = initialTourMode match {
            case Some(tm) =>
              (
                Some(tm),
                currentTourPersonalVehicle.flatMap(x => beamVehicles.get(x).map(_.vehicle))
              ) // This fails after "Current tour vehicle is the same one as being removed
            case _ => getTourModeAndVehicle(chosenTrip.tripClassifier, vehiclesUsed, currentTourPersonalVehicle)
          }

          val updatedTourModeStrategyMaybe =
            if (
              _experiencedBeamPlan
                .getStrategy[TourModeChoiceStrategy](_experiencedBeamPlan.getTourContaining(nextAct))
                .tourMode
                .isEmpty
            ) {
              Some(updateTourModeStrategy(finalTourMode, finalTourVehicle.map(_.id), nextAct, vehiclesUsed))
            } else None

          val currentPlanMode = _experiencedBeamPlan
            .getStrategy[TripModeChoiceStrategy](_experiencedBeamPlan.getTripContaining(nextAct))
            .mode

          // Manually set that personal bike transit vehicles must be driven home because it's not handled in parking
          currentTourPersonalVehicle match {
            case Some(veh)
                if currentPlanMode.contains(BIKE_TRANSIT) && isFirstTripWithinTour(nextAct) && !beamVehicles(
                  veh
                ).vehicle.isSharedVehicle =>
              beamVehicles(veh).vehicle.setMustBeDrivenHome(true)
            case Some(veh)
                if currentPlanMode.contains(BIKE_TRANSIT) && isLastTripWithinTour(nextAct) && !beamVehicles(
                  veh
                ).vehicle.isSharedVehicle =>
              beamVehicles(veh).vehicle.setMustBeDrivenHome(false)
            case _ =>
          }

          currentPlanMode match {
            case None =>
              _experiencedBeamPlan.putStrategy(
                _experiencedBeamPlan.getTripContaining(nextAct),
                TripModeChoiceStrategy(Some(chosenTrip.tripClassifier))
              )
            case Some(strategyMode) if strategyMode == chosenTrip.tripClassifier =>
            case Some(strategyMode @ (DRIVE_TRANSIT | BIKE_TRANSIT | RIDE_HAIL_TRANSIT))
                if (chosenTrip.tripClassifier == WALK_TRANSIT) && data.isWithinTripReplanning =>
              logger.debug(f"Assigning replanning walk_transit trip as part of planned $strategyMode trip")
            case Some(otherMode) =>
              logger.error(
                s"Unexpected difference between trip modes in plans: Chose a ${chosenTrip.tripClassifier} " +
                s"trip with a $otherMode leg in our plans. ChoosesModeData: $data"
              )
          }

          if (
            (updatedTourModeStrategyMaybe.flatMap(
              _.tourVehicle
            ) != currentTourPersonalVehicle) && updatedTourModeStrategyMaybe.nonEmpty && currentTourPersonalVehicle.nonEmpty
          ) {
            logger.error("Why are we keeping a personal vehicle that is different than our tour vehicle?")
          }

          goto(WaitingForDeparture) using data.personData.copy(
            currentTrip = Some(chosenTrip),
            restOfCurrentTrip = chosenTrip.legs.toList,
            currentTripMode = Some(chosenTrip.tripClassifier),
            currentTourPersonalVehicle = currentTourPersonalVehicle,
            currentTourMode = finalTourMode,
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

    if (
      currentTripMode.exists(_.isTeleportation) && !availableVehicles
        .exists(v => BeamVehicle.isSharedTeleportationVehicle(v.id))
    ) {
      logger.error("THIS WILL BE A PROBLEM")
    }

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
      //        println(s"requesting: ${inquiry.requestId}")
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
        streetVehiclesUseIntermodalUse = AccessAndEgress,
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
      choosesModeData.personData.currentTourPersonalVehicle
    )

    var responsePlaceholders = ChoosesModeResponsePlaceholders()
    var requestId: Option[Int] = None
    // Form and send requests
    var householdVehiclesWereNotAvailable = false // to replan when personal vehicles are not available
    currentTripMode match {
      case None =>
        if (hasRideHail) {
          responsePlaceholders = makeResponsePlaceholders(
            withRouting = true,
            withRideHail = true,
            withRideHailTransit = !choosesModeData.isWithinTripReplanning
          )
          makeRideHailRequest()
          if (!choosesModeData.isWithinTripReplanning) {
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
          withTransit = availableModesGivenTourMode.exists(_.isTransit),
          availableStreetVehiclesGivenTourMode,
          possibleEgressVehicles = dummySharedVehicles
        )
      case Some(WALK) =>
        responsePlaceholders = makeResponsePlaceholders(withRouting = true)
        makeRequestWith(withTransit = true, Vector(bodyStreetVehicle))
      case Some(WALK_TRANSIT) =>
        responsePlaceholders = makeResponsePlaceholders(withRouting = true)
        makeRequestWith(
          withTransit = true,
          Vector(bodyStreetVehicle),
          departureBuffer = choosesModeData.personData.numberOfReplanningAttempts * 5
        )
      case Some(CAV) =>
        // Request from household the trip legs to put into trip
        householdRef ! CavTripLegsRequest(bodyVehiclePersonId, currentActivity(choosesModeData.personData))
        responsePlaceholders = makeResponsePlaceholders(withPrivateCAV = true)
      case Some(HOV2_TELEPORTATION) =>
        val vehicles = availableVehicles
          .filter(v => BeamVehicle.isSharedTeleportationVehicle(v.id))
          .map(car_vehicle => car_vehicle.streetVehicle.copy(mode = CAR_HOV2))
        makeRequestWith(withTransit = false, vehicles :+ bodyStreetVehicle)
        responsePlaceholders = makeResponsePlaceholders(withRouting = true)
      case Some(HOV3_TELEPORTATION) =>
        val vehicles = availableVehicles
          .filter(v => BeamVehicle.isSharedTeleportationVehicle(v.id))
          .map(car_vehicle => car_vehicle.streetVehicle.copy(mode = CAR_HOV3))
        makeRequestWith(withTransit = false, vehicles :+ bodyStreetVehicle)
        responsePlaceholders = makeResponsePlaceholders(withRouting = true)
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
                responsePlaceholders = makeResponsePlaceholders(withRouting = true)
              case Some(vehicle) =>
                logger.error(s"Agent ${this.id} is on a ${tripMode.value} trip but has vehicle ${vehicle.toString}")
                makeRequestWith(withTransit = false, Vector(bodyStreetVehicle))
                responsePlaceholders = makeResponsePlaceholders(withRouting = true)
              case _ =>
                makeRequestWith(withTransit = false, Vector(bodyStreetVehicle))
                responsePlaceholders = makeResponsePlaceholders(withRouting = true)
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
              eventsManager.processEvent(
                new ReplanningEvent(
                  departTime,
                  Id.createPersonId(id),
                  getReplanningReasonFrom(
                    choosesModeData.personData,
                    ReservationErrorCode.HouseholdVehicleNotAvailable.entryName
                  ),
                  currentPersonLocation.loc.getX,
                  currentPersonLocation.loc.getY
                )
              )
              householdVehiclesWereNotAvailable = true
              logger.warn("No HH vehicle available so going back to replanning")
            }
            makeRequestWith(withTransit = householdVehiclesWereNotAvailable, vehicles :+ bodyStreetVehicle)
            responsePlaceholders =
              makeResponsePlaceholders(withRouting = true, withRideHail = householdVehiclesWereNotAvailable)
            if (householdVehiclesWereNotAvailable) {
              makeRideHailRequest()
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
                :+ bodyStreetVehicle
              )
              responsePlaceholders = makeResponsePlaceholders(withRouting = true)
            } else {
              // Reset available vehicles so we don't release our car that we've left during this replanning
              resetVehicles = true
              makeRequestWith(withTransit = true, Vector(bodyStreetVehicle))
              responsePlaceholders = makeResponsePlaceholders(withRouting = true)
            }
          case (`lastTripIndex`, Some(currentTourPersonalVehicle)) =>
            // At the end of the tour, only drive home a vehicle that we have also taken away from there.
            makeRequestWith(
              withTransit = true,
              vehicles = Vector(bodyStreetVehicle),
              streetVehiclesIntermodalUse = Access,
              possibleEgressVehicles = availableVehicles
                .map(_.streetVehicle)
                .filter(_.id == currentTourPersonalVehicle)
            )
            responsePlaceholders = makeResponsePlaceholders(withRouting = true)
          case (`lastTripIndex`, None) =>
            // TODO: Is there a way to query egress vehicles near the destination?
            makeRequestWith(
              withTransit = true,
              availableVehicles
                .filter(veh => (veh.streetVehicle.mode == vehicleMode) && veh.vehicle.isSharedVehicle)
                .map(_.streetVehicle) :+ bodyStreetVehicle
            )
            responsePlaceholders = makeResponsePlaceholders(withRouting = true)
          case _ =>
            // Still go for it, because maybe there are some shared vehicles along the route
            makeRequestWith(
              withTransit = true,
              availableVehicles
                .filter(veh => (veh.streetVehicle.mode == vehicleMode) && veh.vehicle.isSharedVehicle)
                .map(_.streetVehicle)
              :+ bodyStreetVehicle
            )
            responsePlaceholders = makeResponsePlaceholders(withRouting = true)
        }
      case Some(RIDE_HAIL | RIDE_HAIL_POOLED) if choosesModeData.isWithinTripReplanning =>
        // Give up on all ride hail after a failure
        responsePlaceholders = makeResponsePlaceholders(withRouting = true)
        makeRequestWith(withTransit = true, Vector(bodyStreetVehicle))
      case Some(RIDE_HAIL | RIDE_HAIL_POOLED) =>
        responsePlaceholders = makeResponsePlaceholders(withRouting = true, withRideHail = true)
        makeRequestWith(withTransit = false, Vector(bodyStreetVehicle)) // We need a WALK alternative if RH fails
        makeRideHailRequest()
      case Some(RIDE_HAIL_TRANSIT) if choosesModeData.isWithinTripReplanning =>
        // Give up on ride hail transit after a failure, too complicated, but try regular ride hail again
        responsePlaceholders = makeResponsePlaceholders(withRouting = true, withRideHail = true)
        makeRequestWith(withTransit = true, Vector(bodyStreetVehicle))
        makeRideHailRequest()
      case Some(RIDE_HAIL_TRANSIT) =>
        responsePlaceholders = makeResponsePlaceholders(withRideHailTransit = true)
        requestId = makeRideHailTransitRoutingRequest(bodyStreetVehicle)
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

  private def chooseTourModeAndVehicle(
    currentTourStrategy: TourModeChoiceStrategy,
    currentTripMode: Option[BeamMode],
    availableVehicles: Vector[VehicleOrToken],
    choosesModeData: ChoosesModeData,
    firstLegItineraries: Option[Vector[EmbodiedBeamTrip]] = None
  ): (Option[BeamTourMode], Option[Id[BeamVehicle]]) = {
    val availableModes: Seq[BeamMode] = availableModesForPerson(matsimPlan.getPerson, choosesModeData.excludeModes)
    val nextAct = nextActivity(choosesModeData.personData).get
    val departTime = _currentTick.get
    currentTourStrategy.tourMode match {
      case Some(tourMode) => (Some(tourMode), currentTourStrategy.tourVehicle)
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
              firstLegItineraries
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
            val chosenTourVehicle: Option[Id[BeamVehicle]] = out match {
              case Some(CAR_BASED) =>
                availableVehicles
                  .find(veh =>
                    (veh.vehicle.beamVehicleType.vehicleCategory == VehicleCategory.Car) & (!veh.vehicle.isSharedVehicle)
                  )
                  .map(_.id)
              case Some(BIKE_BASED) =>
                availableVehicles
                  .find(veh =>
                    (veh.vehicle.beamVehicleType.vehicleCategory == VehicleCategory.Bike) & (!veh.vehicle.isSharedVehicle)
                  )
                  .map(_.id)
              case _ =>
                None
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
            val (chosenTourMode, tourVehicle) =
              getTourModeAndVehicle(tripMode, availableVehicles, choosesModeData.personData.currentTourPersonalVehicle)
            updateTourModeStrategy(chosenTourMode, tourVehicle.map(_.id), nextAct, availableVehicles)

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
            (chosenTourMode, tourVehicle.map(_.id))
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
      logger.warn(
        f"Replacing planned ${st.mode.get} trip with none because of conflict with tour mode ${newTourMode.get}"
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
