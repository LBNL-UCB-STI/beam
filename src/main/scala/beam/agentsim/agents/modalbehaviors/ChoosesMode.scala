package beam.agentsim.agents.modalbehaviors

import akka.actor.{ActorRef, FSM}
import akka.pattern.pipe
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents._
import beam.agentsim.agents.household.HouseholdActor.{MobilityStatusInquiry, MobilityStatusResponse, ReleaseVehicle}
import beam.agentsim.agents.modalbehaviors.ChoosesMode._
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.{ActualVehicle, Token, VehicleOrToken}
import beam.agentsim.agents.ridehail.RideHailLegType.{Access, Egress, _}
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
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.resources.ReservationErrorCode
import beam.agentsim.events.{ModeChoiceEvent, ReplanningEvent, SpaceTime}
import beam.agentsim.infrastructure.{ParkingInquiry, ParkingInquiryResponse, ZonalParkingManager}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.router.BeamRouter.IntermodalUse._
import beam.router.BeamRouter._
import beam.router.Modes.{isRideHailTransit, BeamMode}
import beam.router.Modes.BeamMode._
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
import scala.util.{Failure, Success, Try}

/**
  * BEAM
  */
trait ChoosesMode {
  this: PersonAgent => // Self type restricts this trait to only mix into a PersonAgent

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
          .flatMap { n =>
            Try(BeamMode.fromString(n)).toOption.flatten.toSeq
          }
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
    nextStateData match {
      // If I am already on a tour in a vehicle, only that vehicle is available to me
      case ChoosesModeData(
            BasePersonData(_, _, _, _, _, Some(vehicle), _, _, _, _, _, _, _, _, _),
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _
          ) =>
        self ! MobilityStatusResponse(Vector(beamVehicles(vehicle)), getCurrentTriggerIdOrGenerate)
      // Only need to get available street vehicles if our mode requires such a vehicle
      case ChoosesModeData(
            BasePersonData(
              _,
              _,
              _,
              _,
              Some(HOV2_TELEPORTATION | HOV3_TELEPORTATION),
              _,
              _,
              _,
              _,
              _,
              _,
              _,
              _,
              _,
              _
            ),
            currentLocation,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _
          ) =>
        val teleportationVehicle = createSharedTeleportationVehicle(currentLocation)
        val vehicles = Vector(ActualVehicle(teleportationVehicle))
        self ! MobilityStatusResponse(vehicles, getCurrentTriggerIdOrGenerate)
      // Only need to get available street vehicles if our mode requires such a vehicle
      case ChoosesModeData(
            BasePersonData(
              currentActivityIndex,
              _,
              _,
              _,
              plansModeOption @ (None | Some(CAR | BIKE | DRIVE_TRANSIT | BIKE_TRANSIT)),
              _,
              _,
              _,
              _,
              _,
              _,
              _,
              _,
              _,
              _
            ),
            currentLocation,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _,
            _
          ) =>
        implicit val executionContext: ExecutionContext = context.system.dispatcher
        plansModeOption match {
          case Some(CAR | DRIVE_TRANSIT | CAR_HOV2 | CAR_HOV3) => // TODO: Add HOV modes here too
            requestAvailableVehicles(
              vehicleFleets,
              currentLocation,
              _experiencedBeamPlan.activities(currentActivityIndex),
              Some(VehicleCategory.Car)
            ) pipeTo self
          case Some(BIKE | BIKE_TRANSIT) =>
            requestAvailableVehicles(
              vehicleFleets,
              currentLocation,
              _experiencedBeamPlan.activities(currentActivityIndex),
              Some(VehicleCategory.Bike)
            ) pipeTo self
          case _ =>
            requestAvailableVehicles(
              vehicleFleets,
              currentLocation,
              _experiencedBeamPlan.activities(currentActivityIndex)
            ) pipeTo self
        }

      // Otherwise, send empty list to self
      case _ =>
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
      val currentPersonLocation = choosesModeData.currentLocation
      val availableModes: Seq[BeamMode] = availableModesForPerson(
        matsimPlan.getPerson
      ).filterNot(mode => choosesModeData.excludeModes.contains(mode))
      // Make sure the current mode is allowable
      val replanningIsAvailable =
        choosesModeData.personData.numberOfReplanningAttempts < beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.maximumNumberOfReplanningAttempts
      val correctedCurrentTourMode = choosesModeData.personData.currentTourMode match {
        case Some(mode @ (HOV2_TELEPORTATION | HOV3_TELEPORTATION))
            if availableModes.contains(CAR) && replanningIsAvailable =>
          Some(mode)
        case Some(mode) if availableModes.contains(mode) && replanningIsAvailable => Some(mode)
        case Some(mode) if availableModes.contains(mode)                          => Some(WALK)
        case None if !replanningIsAvailable                                       => Some(WALK)
        case _                                                                    => None
      }

      val bodyStreetVehicle = createBodyStreetVehicle(currentPersonLocation)
      val nextAct = nextActivity(choosesModeData.personData).get
      val departTime = _currentTick.get

      var availablePersonalStreetVehicles =
        correctedCurrentTourMode match {
          case None | Some(CAR | BIKE) =>
            // In these cases, a personal vehicle will be involved, but filter out teleportation vehicles
            newlyAvailableBeamVehicles.filterNot(v => BeamVehicle.isSharedTeleportationVehicle(v.id))
          case Some(HOV2_TELEPORTATION | HOV3_TELEPORTATION) =>
            // In these cases, also include teleportation vehicles
            newlyAvailableBeamVehicles
          case Some(DRIVE_TRANSIT | BIKE_TRANSIT) =>
            val tour = _experiencedBeamPlan.getTourContaining(nextAct)
            val tripIndexOfElement = tour
              .tripIndexOfElement(nextAct)
              .getOrElse(throw new IllegalArgumentException(s"Element [$nextAct] not found"))
            if (tripIndexOfElement == 0 || tripIndexOfElement == tour.trips.size - 1) {
              newlyAvailableBeamVehicles
            } else {
              Vector()
            }
          case _ =>
            Vector()
        }

      def makeRequestWith(
        withTransit: Boolean,
        vehicles: Vector[StreetVehicle],
        streetVehiclesIntermodalUse: IntermodalUse = IntermodalUse.Access,
        possibleEgressVehicles: IndexedSeq[StreetVehicle] = IndexedSeq.empty
      ): Unit = {
        router ! RoutingRequest(
          currentPersonLocation.loc,
          nextAct.getCoord,
          departTime,
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
          asPooled = !choosesModeData.personData.currentTourMode.contains(RIDE_HAIL),
          withWheelchair = wheelchairUser,
          legType = Some(Direct),
          requestTime = _currentTick.getOrElse(0),
          rideHailServiceSubscription = attributes.rideHailServiceSubscription,
          requester = self,
          triggerId = getCurrentTriggerIdOrGenerate
        )
        //        println(s"requesting: ${inquiry.requestId}")
        rideHailManager ! inquiry
      }

      def makeRideHailTransitRoutingRequest(
        bodyStreetVehicleRequestParam: StreetVehicle,
        mode: BeamMode = RIDE_HAIL_TRANSIT
      ): Option[Int] = {
        val buffer: Int = mode match {
          case RIDE_HAIL_TRANSIT => beamServices.beamConfig.beam.routing.r5.rideHailTransitRoutingBufferInSeconds
          case RIDE_HAIL_POOLED_TRANSIT =>
            beamServices.beamConfig.beam.routing.r5.rideHailPooledTransitRoutingBufferInSeconds
          case mode @ _ =>
            logger.warn(s"Candidate rh transit trip has mode $mode")
            beamServices.beamConfig.beam.routing.r5.rideHailPooledTransitRoutingBufferInSeconds
        }
        val startWithWaitBuffer = buffer + departTime
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

      val hasRideHail = availableModes.contains(RIDE_HAIL)

      var responsePlaceholders = ChoosesModeResponsePlaceholders()
      var requestId: Option[Int] = None
      // Form and send requests

      var householdVehiclesWereNotAvailable = false // to replan when personal vehicles are not available
      correctedCurrentTourMode match {
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
          makeRequestWith(
            withTransit = availableModes.exists(_.isTransit),
            newlyAvailableBeamVehicles.map(_.streetVehicle) :+ bodyStreetVehicle,
            possibleEgressVehicles = dummySharedVehicles
          )
        case Some(WALK) =>
          responsePlaceholders = makeResponsePlaceholders(withRouting = true)
          makeRequestWith(withTransit = true, Vector(bodyStreetVehicle))
        case Some(WALK_TRANSIT) =>
          responsePlaceholders = makeResponsePlaceholders(withRouting = true)
          makeRequestWith(withTransit = true, Vector(bodyStreetVehicle))
        case Some(CAV) =>
          // Request from household the trip legs to put into trip
          householdRef ! CavTripLegsRequest(bodyVehiclePersonId, currentActivity(choosesModeData.personData))
          responsePlaceholders = makeResponsePlaceholders(withPrivateCAV = true)
        case Some(HOV2_TELEPORTATION) =>
          val vehicles = filterStreetVehiclesForQuery(newlyAvailableBeamVehicles.map(_.streetVehicle), CAR)
            .map(car_vehicle => car_vehicle.copy(mode = CAR_HOV2))
          makeRequestWith(withTransit = false, vehicles :+ bodyStreetVehicle)
          responsePlaceholders = makeResponsePlaceholders(withRouting = true)
        case Some(HOV3_TELEPORTATION) =>
          val vehicles = filterStreetVehiclesForQuery(newlyAvailableBeamVehicles.map(_.streetVehicle), CAR)
            .map(car_vehicle => car_vehicle.copy(mode = CAR_HOV3))
          makeRequestWith(withTransit = false, vehicles :+ bodyStreetVehicle)
          responsePlaceholders = makeResponsePlaceholders(withRouting = true)
        case Some(tourMode @ (CAR | BIKE)) =>
          val maybeLeg = _experiencedBeamPlan.getPlanElements
            .get(_experiencedBeamPlan.getPlanElements.indexOf(nextAct) - 1) match {
            case l: Leg => Some(l)
            case _      => None
          }
          maybeLeg.map(_.getRoute) match {
            case Some(networkRoute: NetworkRoute) =>
              val maybeVehicle =
                filterStreetVehiclesForQuery(newlyAvailableBeamVehicles.map(_.streetVehicle), tourMode).headOption
              maybeVehicle match {
                case Some(vehicle) =>
                  router ! matsimLegToEmbodyRequest(
                    networkRoute,
                    vehicle,
                    departTime,
                    tourMode,
                    beamServices,
                    choosesModeData.currentLocation.loc,
                    nextAct.getCoord,
                    triggerId
                  )
                  responsePlaceholders = makeResponsePlaceholders(withRouting = true)
                case _ =>
                  makeRequestWith(withTransit = false, Vector(bodyStreetVehicle))
                  responsePlaceholders = makeResponsePlaceholders(withRouting = true)
                  logger.error(
                    "No vehicle available for existing route of person {} trip of mode {} even though it was created in their plans",
                    body.id,
                    tourMode
                  )
              }
            case _ =>
              val vehicles = filterStreetVehiclesForQuery(newlyAvailableBeamVehicles.map(_.streetVehicle), tourMode)
                .map(vehicle => {
                  vehicle.mode match {
                    case CAR => vehicle.copy(mode = tourMode)
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
          val LastTripIndex = currentTour(choosesModeData.personData).trips.size - 1
          val tripIndexOfElement = currentTour(choosesModeData.personData)
            .tripIndexOfElement(nextAct)
            .getOrElse(throw new IllegalArgumentException(s"Element [$nextAct] not found"))
          (
            tripIndexOfElement,
            choosesModeData.personData.currentTourPersonalVehicle
          ) match {
            case (0, _) if !choosesModeData.isWithinTripReplanning =>
              // We use our car if we are not replanning, otherwise we end up doing a walk transit (catch-all below)
              // we do not send parking inquiry here, instead we wait for drive_transit route to come back and we use
              // actual location of transit station
              makeRequestWith(
                withTransit = true,
                filterStreetVehiclesForQuery(newlyAvailableBeamVehicles.map(_.streetVehicle), vehicleMode)
                :+ bodyStreetVehicle
              )
              responsePlaceholders = makeResponsePlaceholders(withRouting = true)
            case (LastTripIndex, Some(currentTourPersonalVehicle)) =>
              // At the end of the tour, only drive home a vehicle that we have also taken away from there.
              makeRequestWith(
                withTransit = true,
                newlyAvailableBeamVehicles
                  .map(_.streetVehicle)
                  .filter(_.id == currentTourPersonalVehicle) :+ bodyStreetVehicle,
                streetVehiclesIntermodalUse = IntermodalUse.Egress
              )
              responsePlaceholders = makeResponsePlaceholders(withRouting = true)
            case _ =>
              // Reset available vehicles so we don't release our car that we've left during this replanning
              availablePersonalStreetVehicles = Vector()
              makeRequestWith(withTransit = true, Vector(bodyStreetVehicle))
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
        case Some(RIDE_HAIL_TRANSIT | RIDE_HAIL_POOLED_TRANSIT) if choosesModeData.isWithinTripReplanning =>
          // Give up on ride hail transit after a failure, too complicated, but try regular ride hail again
          responsePlaceholders = makeResponsePlaceholders(withRouting = true, withRideHail = true)
          makeRequestWith(withTransit = true, Vector(bodyStreetVehicle))
          makeRideHailRequest()
        case Some(RIDE_HAIL_TRANSIT | RIDE_HAIL_POOLED_TRANSIT) =>
          responsePlaceholders = makeResponsePlaceholders(withRideHailTransit = true)
          requestId = makeRideHailTransitRoutingRequest(bodyStreetVehicle)
        case Some(m) =>
          logDebug(m.toString)
      }
      val newPersonData = choosesModeData.copy(
        personData = choosesModeData.personData
          .copy(currentTourMode = if (householdVehiclesWereNotAvailable) None else correctedCurrentTourMode),
        availablePersonalStreetVehicles = availablePersonalStreetVehicles,
        allAvailableStreetVehicles = newlyAvailableBeamVehicles,
        routingResponse = responsePlaceholders.routingResponse,
        rideHail2TransitRoutingResponse = responsePlaceholders.rideHail2TransitRoutingResponse,
        rideHail2TransitRoutingRequestId = requestId,
        rideHailResult = responsePlaceholders.rideHailResult,
        rideHail2TransitAccessResult = responsePlaceholders.rideHail2TransitAccessResult,
        rideHail2TransitEgressResult = responsePlaceholders.rideHail2TransitEgressResult,
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

      val rhTransitTrip = modeChoiceCalculator(
        theRouterResult.itineraries
          .filter(itin => isRideHailTransit(itin.tripClassifier))
          .toIndexedSeq,
        matsimPlan.getPerson.getCustomAttributes
          .get("beam-attributes")
          .asInstanceOf[AttributesOfIndividual],
        nextActivity(choosesModeData.personData),
        Some(currentActivity(choosesModeData.personData)),
        Some(matsimPlan.getPerson)
      )

      val makePooledRequests = choosesModeData.personData.currentTourMode match {
        case Some(RIDE_HAIL_POOLED_TRANSIT) => true
        case _                              => false
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
              (makeRideHailRequestFromBeamLeg(accessSegment, makePooledRequests, Some(Access)), None)
            } else {
              (None, Some(RideHailResponse.dummyWithError(RideHailNotRequestedError)))
            }
          val (egressId, egressResult) =
            if (
              (egressSegment.map(_.travelPath.distanceInM).sum > 0) & egressSegment
                .exists(l => l.mode.isRideHail | l.mode == CAR)
            ) {
              (makeRideHailRequestFromBeamLeg(egressSegment.toVector, makePooledRequests, Some(Egress)), None)
            } else {
              (None, Some(RideHailResponse.dummyWithError(RideHailNotRequestedError)))
            }
          choosesModeData.copy(
            rideHail2TransitRoutingResponse = Some(rhTransitTrip.get),
            rideHail2TransitAccessInquiryId = accessId,
            rideHail2TransitEgressInquiryId = egressId,
            rideHail2TransitAccessResult = accessResult,
            rideHail2TransitEgressResult = egressResult
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

      val dummyVehiclesPresented = makeVehicleRequestsForDummySharedVehicles(response.itineraries)
      val newData = if (dummyVehiclesPresented) {
        choosesModeData.copy(
          routingResponse = Some(response),
          parkingRequestIds = newParkingRequestIds
        )
      } else {
        choosesModeData.copy(
          routingResponse = Some(correctRoutingResponse(response)),
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
      //      println(s"receiving response: ${theRideHailResult}")
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
      val newPersonData = choosesModeData.copy(
        parkingResponses = choosesModeData.parkingResponses +
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

  def shouldAttemptRideHail2Transit(
    driveTransitTrip: Option[EmbodiedBeamTrip],
    rideHail2TransitResult: Option[RideHailResponse]
  ): Boolean = {
    driveTransitTrip.isDefined && driveTransitTrip.get.legs
      .exists(leg => beamScenario.rideHailTransitModes.contains(leg.beamLeg.mode)) &&
    rideHail2TransitResult.getOrElse(RideHailResponse.DUMMY).error.isEmpty // NOTE: will this ever be nonempty?
  }

  def makeRideHailRequestFromBeamLeg(
    legs: Seq[BeamLeg],
    asPooled: Boolean,
    legType: Option[RideHailLegType]
  ): Option[Int] = {
    val inquiry = RideHailRequest(
      RideHailInquiry,
      bodyVehiclePersonId,
      beamServices.geo.wgs2Utm(legs.head.travelPath.startPoint.loc),
      legs.head.startTime,
      beamServices.geo.wgs2Utm(legs.last.travelPath.endPoint.loc),
      asPooled = asPooled,
      withWheelchair = wheelchairUser,
      legType = legType,
      requestTime = _currentTick.getOrElse(0),
      rideHailServiceSubscription = attributes.rideHailServiceSubscription,
      requester = self,
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

  def createRideHail2TransitItin(
    rideHail2TransitAccessResult: RideHailResponse,
    rideHail2TransitEgressResult: RideHailResponse,
    driveTransitTrip: EmbodiedBeamTrip
  ): Vector[EmbodiedBeamTrip] = {
    if (!isRideHailTransit(driveTransitTrip.tripClassifier)) {
      Vector.empty[EmbodiedBeamTrip]
    } else if (
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

  private def createRideHailTransitTrip(
    driveTransitTrip: EmbodiedBeamTrip,
    tncAccessLeg: Vector[EmbodiedBeamLeg],
    timeToCustomer: Int,
    tncEgressLeg: Vector[EmbodiedBeamLeg]
  ): Option[EmbodiedBeamTrip] = {
    val buffer: Int = driveTransitTrip.tripClassifier match {
      case RIDE_HAIL_TRANSIT => beamServices.beamConfig.beam.routing.r5.rideHailTransitRoutingBufferInSeconds
      case RIDE_HAIL_POOLED_TRANSIT =>
        beamServices.beamConfig.beam.routing.r5.rideHailPooledTransitRoutingBufferInSeconds
      case mode @ _ =>
        logger.warn(s"Candidate rh transit trip has mode $mode")
        beamServices.beamConfig.beam.routing.r5.rideHailPooledTransitRoutingBufferInSeconds
    }
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
          buffer.toDouble + latenessToFirstTransitLeg.toDouble
        val extraWaitTimeBuffer = rhLegs.last.beamLeg.endTime -
          tncAccessLeg.map(_.beamLeg.duration).sum - timeToCustomer - _currentTick.get - startTimeBufferForWaiting
        (extraWaitTimeBuffer.floor.toInt, startTimeBufferForWaiting.floor.toInt)
    }

    if (extraWaitTimeBuffer > 0) {
      Some(
        surroundWithWalkLegsIfNeededAndMakeTrip(
          tncAccessLeg.map(leg =>
            leg.copy(beamLeg = leg.beamLeg.updateStartTime(leg.beamLeg.startTime - accessLegAdjustment))
          ) ++ transitLegs ++ tncEgressLeg
        )
      )
    } else None
  }

  def addParkingCostToItins(
    itineraries: Seq[EmbodiedBeamTrip],
    parkingResponses: Map[VehicleOnTrip, ParkingInquiryResponse]
  ): Seq[EmbodiedBeamTrip] = {
    itineraries.map { itin =>
      itin.tripClassifier match {
        case CAR | DRIVE_TRANSIT | BIKE_TRANSIT | BIKE =>
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

  def mustBeDrivenHome(vehicle: VehicleOrToken): Boolean = {
    vehicle match {
      case ActualVehicle(beamVehicle) =>
        beamVehicle.isMustBeDrivenHome
      case _: Token =>
        false // is not a household vehicle
    }
  }

  def completeChoiceIfReady: PartialFunction[State, State] = {
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
            _,
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
            choosesModeData.personData.currentTourMode
          ).map(surroundWithWalkLegsIfNeededAndMakeTrip)
        case _ =>
          Vector()
      }

      val combinedItinerariesForChoice = rideHailItinerary ++ addParkingCostToItins(
        routingResponse.itineraries,
        parkingResponses
      ) ++ rideHail2TransitIineraries

      val availableModesForTrips: Seq[BeamMode] = availableModesForPerson(matsimPlan.getPerson)
        .filterNot(mode => choosesModeData.excludeModes.contains(mode))

      val filteredItinerariesForChoice = choosesModeData.personData.currentTourMode match {
        case Some(mode) if mode == DRIVE_TRANSIT || mode == BIKE_TRANSIT =>
          val LastTripIndex = currentTour(choosesModeData.personData).trips.size - 1
          val tripIndexOfElement = currentTour(choosesModeData.personData)
            .tripIndexOfElement(nextAct)
            .getOrElse(throw new IllegalArgumentException(s"Element [$nextAct] not found"))
          (
            tripIndexOfElement,
            personData.hasDeparted
          ) match {
            case (0 | LastTripIndex, false) =>
              combinedItinerariesForChoice.filter(_.tripClassifier == mode)
            case _ =>
              combinedItinerariesForChoice.filter(trip =>
                trip.tripClassifier.in(Seq(WALK_TRANSIT, RIDE_HAIL_TRANSIT, WALK))
              )
          }
        case Some(mode) =>
          combinedItinerariesForChoice.filter(_.tripClassifier == mode)
        case _ =>
          combinedItinerariesForChoice
      }

      val itinerariesOfCorrectMode =
        filteredItinerariesForChoice.filter(itin => availableModesForTrips.contains(itin.tripClassifier))

      val attributesOfIndividual =
        matsimPlan.getPerson.getCustomAttributes
          .get("beam-attributes")
          .asInstanceOf[AttributesOfIndividual]
      val availableAlts = Some(itinerariesOfCorrectMode.map(_.tripClassifier).mkString(":"))

      modeChoiceCalculator(
        itinerariesOfCorrectMode,
        attributesOfIndividual,
        nextActivity(choosesModeData.personData),
        Some(currentActivity(choosesModeData.personData)),
        Some(matsimPlan.getPerson)
      ) match {
        case Some(chosenTrip) =>
          filteredItinerariesForChoice.foreach {
            case possibleTrip
                if (possibleTrip != chosenTrip) &&
                  beamScenario.beamConfig.beam.exchange.output.sendNonChosenTripsToSkimmer =>
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
          val dataForNextStep = choosesModeData.copy(
            pendingChosenTrip = Some(chosenTrip),
            availableAlternatives = availableAlts
          )
          goto(FinishingModeChoice) using dataForNextStep
        case None =>
          choosesModeData.personData.currentTourMode match {
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
                goto(FinishingModeChoice) using choosesModeData.copy(
                  pendingChosenTrip = Some(cavTrip),
                  availableAlternatives = availableAlts
                )
              } else {
                val bushwhackingTrip = RoutingWorker.createBushwackingTrip(
                  choosesModeData.currentLocation.loc,
                  nextActivity(choosesModeData.personData).get.getCoord,
                  _currentTick.get,
                  body.toStreetVehicle,
                  geo
                )
                goto(FinishingModeChoice) using choosesModeData.copy(
                  pendingChosenTrip = Some(bushwhackingTrip),
                  availableAlternatives = availableAlts
                )
              }
            case Some(mode) =>
              val currentAct = currentActivity(personData)
              val odFailedSkimmerEvent = createFailedODSkimmerEvent(currentAct, nextAct, mode)
              eventsManager.processEvent(
                odFailedSkimmerEvent
              )
              if (beamServices.beamConfig.beam.exchange.output.activitySimSkimsEnabled) {
                val possibleActivitySimModes: Seq[ActivitySimPathType] =
                  determineActivitySimPathTypesFromBeamMode(
                    choosesModeData.personData.currentTourMode,
                    Some(currentAct)
                  )
                createFailedActivitySimSkimmerEvent(odFailedSkimmerEvent, possibleActivitySimModes).foreach(ev =>
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
                  nextAct.getCoord.getX,
                  nextAct.getCoord.getY
                )
              )
              //give another chance to make a choice without predefined mode
              val availableVehicles =
                if (mode.isTeleportation)
                  //we need to remove our teleportation vehicle since we cannot use it if it's not a teleportation mode
                  choosesModeData.allAvailableStreetVehicles.filterNot(vehicle =>
                    BeamVehicle.isSharedTeleportationVehicle(vehicle.id)
                  )
                else choosesModeData.allAvailableStreetVehicles
              self ! MobilityStatusResponse(availableVehicles, getCurrentTriggerId.get)
              logger.debug(
                "Person {} replanning because planned mode {} not available",
                body.id,
                mode.toString
              )
              stay() using ChoosesModeData(
                personData = personData.copy(currentTourMode = None),
                currentLocation = choosesModeData.currentLocation,
                excludeModes = choosesModeData.excludeModes :+ mode,
                isWithinTripReplanning =
                  choosesModeData.isWithinTripReplanning | beamScenario.beamConfig.beam.agentsim.agents.modalBehaviors.replanningWhenNoAvailableRoute
              )
            case _ =>
              // Bad things happen but we want them to continue their day, so we signal to downstream that trip should be made to be expensive
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
              logger.warn(
                "Person {} forced into long walk trip because nothing is available",
                body.id
              )
              goto(FinishingModeChoice) using choosesModeData.copy(
                pendingChosenTrip = Some(expensiveWalkTrip),
                availableAlternatives = availableAlts
              )
          }
      }
  }

  /**
    * Creates None, RIDE_HAIL, RIDE_HAIL_POOLED or both legs from a TravelProposal
    * @param travelProposal the proposal
    * @param requiredMode the required mode of the trip. If it's None then both modes are possible.
    * @return An empty vector in case it cannot satisfy conditions.
    */
  private def travelProposalToRideHailLegs(
    travelProposal: RideHailManager.TravelProposal,
    rideHailManagerName: String,
    requiredMode: Option[BeamMode]
  ): Vector[Vector[EmbodiedBeamLeg]] = {
    val origLegs = travelProposal.toEmbodiedBeamLegsForCustomer(bodyVehiclePersonId, rideHailManagerName)
    travelProposal.poolingInfo match {
      case Some(poolingInfo)
          if !requiredMode.contains(RIDE_HAIL)
            && travelProposal.modeOptions.contains(RIDE_HAIL_POOLED) =>
        val pooledLegs = origLegs.map { origLeg =>
          origLeg.copy(
            cost = origLeg.cost * poolingInfo.costFactor,
            isPooledTrip = origLeg.isRideHail,
            beamLeg = origLeg.beamLeg.scaleLegDuration(poolingInfo.timeFactor)
          )
        }
        val consistentPooledLegs = EmbodiedBeamLeg.makeLegsConsistent(pooledLegs)
        if (travelProposal.modeOptions.contains(RIDE_HAIL)) {
          Vector(origLegs, consistentPooledLegs)
        } else {
          Vector(consistentPooledLegs)
        }
      case _
          if !requiredMode.contains(RIDE_HAIL_POOLED)
            && travelProposal.modeOptions.contains(RIDE_HAIL) =>
        Vector(origLegs)
      case _ =>
        // current tour mode doesn't correspond to mode options provided by travel proposal
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
    val (origCoord, destCoord) = (originActivity.getCoord, destinationActivity.getCoord)
    val (origin, destination) =
      if (beamScenario.tazTreeMap.tazListContainsGeoms) {
        val startTaz = getTazFromActivity(originActivity)
        val endTaz = getTazFromActivity(destinationActivity)
        (startTaz.toString, endTaz.toString)
      } else {
        beamScenario.exchangeGeoMap match {
          case Some(geoMap) =>
            val origGeo = geoMap.getTAZ(origCoord)
            val destGeo = geoMap.getTAZ(destCoord)
            (origGeo.tazId.toString, destGeo.tazId.toString)
          case None =>
            val origGeo = beamScenario.tazTreeMap.getTAZ(origCoord)
            val destGeo = beamScenario.tazTreeMap.getTAZ(destCoord)
            (origGeo.tazId.toString, destGeo.tazId.toString)
        }
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
    failedODSkimmerEvent: ODSkimmerFailedTripEvent,
    modes: Seq[ActivitySimPathType]
  ): Seq[ActivitySimSkimmerFailedTripEvent] = {
    modes.flatMap { pathType =>
      rideHailModeToFleets.get(pathType) match {
        case Some(fleets) =>
          fleets.map(fleet =>
            ActivitySimSkimmerFailedTripEvent(
              origin = failedODSkimmerEvent.origin,
              destination = failedODSkimmerEvent.destination,
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
              origin = failedODSkimmerEvent.origin,
              destination = failedODSkimmerEvent.destination,
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

  when(FinishingModeChoice, stateTimeout = Duration.Zero) { case Event(StateTimeout, data: ChoosesModeData) =>
    val pendingTrip = data.pendingChosenTrip.get
    val (tick, triggerId) = releaseTickAndTriggerId()
    val chosenTrip = makeFinalCorrections(pendingTrip, tick, currentActivity(data.personData).getEndTime)

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

    val modeChoiceEvent = new ModeChoiceEvent(
      tick,
      id,
      chosenTrip.tripClassifier.value,
      data.personData.currentTourMode.map(_.value).getOrElse(""),
      data.expectedMaxUtilityOfLatestChoice.getOrElse[Double](Double.NaN),
      _experiencedBeamPlan.activities(data.personData.currentActivityIndex).getLinkId.toString,
      data.availableAlternatives.get,
      data.availablePersonalStreetVehicles.nonEmpty,
      chosenTrip.legs.view.map(_.beamLeg.travelPath.distanceInM).sum,
      _experiencedBeamPlan.tourIndexOfElement(nextActivity(data.personData).get),
      chosenTrip,
      _experiencedBeamPlan.activities(data.personData.currentActivityIndex).getType,
      nextActivity(data.personData).get.getType,
      tripId
    )
    eventsManager.processEvent(modeChoiceEvent)

    data.personData.currentTourMode match {
      case Some(HOV2_TELEPORTATION | HOV3_TELEPORTATION) =>
        scheduler ! CompletionNotice(
          triggerId,
          Vector(
            ScheduleTrigger(
              PersonDepartureTrigger(math.max(chosenTrip.legs.head.beamLeg.startTime, tick)),
              self
            )
          )
        )

        goto(Teleporting) using data.personData.copy(
          currentTrip = Some(chosenTrip),
          restOfCurrentTrip = List()
        )

      case _ =>
        val (vehiclesUsed, vehiclesNotUsed) = data.availablePersonalStreetVehicles
          .partition(vehicle => chosenTrip.vehiclesInTrip.contains(vehicle.id))

        var isCurrentPersonalVehicleVoided = false
        vehiclesNotUsed.collect { case ActualVehicle(vehicle) =>
          data.personData.currentTourPersonalVehicle.foreach { currentVehicle =>
            if (currentVehicle == vehicle.id) {
              // TODO It used to be logError, however I am not sure if it is really an error
              logDebug(
                s"Current tour vehicle is the same as the one being removed: $currentVehicle - ${vehicle.id} - $data"
              )
              isCurrentPersonalVehicleVoided = true
            }
          }
          beamVehicles.remove(vehicle.id)
          vehicle.getManager.get ! ReleaseVehicle(vehicle, triggerId)
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
        goto(WaitingForDeparture) using data.personData.copy(
          currentTrip = Some(chosenTrip),
          restOfCurrentTrip = chosenTrip.legs.toList,
          currentTourMode = data.personData.currentTourMode
            .orElse(Some(chosenTrip.tripClassifier)),
          currentTourPersonalVehicle =
            if (isCurrentPersonalVehicleVoided)
              vehiclesUsed.headOption.filter(mustBeDrivenHome).map(_.id)
            else
              data.personData.currentTourPersonalVehicle
                .orElse(vehiclesUsed.headOption.filter(mustBeDrivenHome).map(_.id)),
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
}

object ChoosesMode {

  case class TripIdentifier(tripClassifier: BeamMode, legModes: IndexedSeq[BeamMode]) {

    def isAppropriateTrip(trip: EmbodiedBeamTrip): Boolean =
      trip.tripClassifier == tripClassifier &&
      TripIdentifier.filterMainVehicles(trip).map(_.beamLeg.mode) == legModes
  }

  object TripIdentifier {

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
    excludeModes: Vector[BeamMode] = Vector(),
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
      copy(
        personData = personData.copy(currentLegPassengerScheduleIndex = currentLegPassengerScheduleIndex)
      )

    override def hasParkingBehaviors: Boolean = true

    override def geofence: Option[Geofence] = None

    override def legStartsAt: Option[Int] = None

  }

  case class MobilityStatusWithLegs(
    responses: Seq[(EmbodiedBeamTrip, EmbodiedBeamLeg, MobilityStatusResponse)]
  )

  case class ChoosesModeResponsePlaceholders(
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

  def getActivityEndTime(activity: Activity, beamServices: BeamServices): Int = {
    (if (activity.getEndTime.equals(Double.NegativeInfinity))
       Time.parseTime(beamServices.beamConfig.matsim.modules.qsim.endTime)
     else
       activity.getEndTime).toInt
  }

}
