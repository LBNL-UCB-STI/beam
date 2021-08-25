package beam.agentsim.agents.modalbehaviors

import akka.actor.{ActorRef, FSM}
import akka.pattern.pipe
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent.{ChoosingMode, _}
import beam.agentsim.agents._
import beam.agentsim.agents.household.HouseholdActor.{MobilityStatusInquiry, MobilityStatusResponse, ReleaseVehicle}
import beam.agentsim.agents.modalbehaviors.ChoosesMode._
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.{ActualVehicle, Token, VehicleOrToken}
import beam.agentsim.agents.ridehail.{RideHailInquiry, RideHailRequest, RideHailResponse}
import beam.agentsim.agents.vehicles.AccessErrorCodes.RideHailNotRequestedError
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{PersonIdWithActorRef, _}
import beam.agentsim.events.{ModeChoiceEvent, SpaceTime}
import beam.agentsim.infrastructure.{ParkingInquiry, ParkingInquiryResponse, ZonalParkingManager}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{WALK, _}
import beam.router.model.{BeamLeg, EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.sim.{BeamServices, Geofence}
import beam.sim.population.AttributesOfIndividual
import beam.utils.logging.pattern.ask
import beam.utils.plan.sampling.AvailableModeUtils._
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.population.{Activity, Leg}
import org.matsim.api.core.v01.Id
import org.matsim.core.population.routes.NetworkRoute
import org.matsim.core.utils.misc.Time

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import beam.agentsim.infrastructure.parking.GeoLevel
import beam.router.{Modes, RoutingWorker}

/**
  * BEAM
  */
trait ChoosesMode {
  this: PersonAgent => // Self type restricts this trait to only mix into a PersonAgent

  private val emergencyGeoId =
    GeoLevel.getSpecialGeoIds(beamServices.beamConfig.beam.agentsim.taz.parkingManager.level)._1

  val dummyRHVehicle: StreetVehicle = createDummyVehicle(
    "dummyRH",
    beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId,
    CAR,
    asDriver = false,
    needsToCalculateCost = true
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
          asDriver = true,
          needsToCalculateCost = true
        )
      case VehicleCategory.Bike =>
        createDummyVehicle(
          "dummySharedBike",
          beamServices.beamConfig.beam.agentsim.agents.vehicles.dummySharedBike.vehicleTypeId,
          BIKE,
          asDriver = true,
          needsToCalculateCost = true
        )
      case category @ _ =>
        throw new IllegalArgumentException(
          s"Unsupported shared vehicle category $category. Only CAR | BIKE are supported."
        )
    }
    .toIndexedSeq

  private def createDummyVehicle(
    id: String,
    vehicleTypeId: String,
    mode: BeamMode,
    asDriver: Boolean,
    needsToCalculateCost: Boolean
  ) =
    StreetVehicle(
      Id.create(id, classOf[BeamVehicle]),
      Id.create(
        vehicleTypeId,
        classOf[BeamVehicleType]
      ),
      SpaceTime(0.0, 0.0, 0),
      mode,
      asDriver = asDriver,
      needsToCalculateCost = needsToCalculateCost
    )

  def bodyVehiclePersonId: PersonIdWithActorRef = PersonIdWithActorRef(id, self)

  def boundingBox: Envelope

  def currentTourBeamVehicle: Option[BeamVehicle] = {
    stateData match {
      case data: ChoosesModeData =>
        data.personData.currentTourPersonalVehicle match {
          case Some(personalVehicle) =>
            Option(
              beamVehicles(personalVehicle)
                .asInstanceOf[ActualVehicle]
                .vehicle
            )
          case _ => None
        }
      case data: BasePersonData =>
        data.currentTourPersonalVehicle match {
          case Some(personalVehicle) =>
            Option(
              beamVehicles(personalVehicle)
                .asInstanceOf[ActualVehicle]
                .vehicle
            )
          case _ => None
        }
      case _ =>
        None
    }
  }

  onTransition { case _ -> ChoosingMode =>
    nextStateData match {
      // If I am already on a tour in a vehicle, only that vehicle is available to me
      case ChoosesModeData(
            BasePersonData(_, _, _, _, _, Some(vehicle), _, _, _, _, _, _),
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
              currentActivityIndex,
              _,
              _,
              _,
              None | Some(CAR | BIKE | DRIVE_TRANSIT | BIKE_TRANSIT),
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
            _
          ) =>
        implicit val executionContext: ExecutionContext = context.system.dispatcher
        requestAvailableVehicles(
          vehicleFleets,
          currentLocation,
          _experiencedBeamPlan.activities(currentActivityIndex)
        ) pipeTo self
      // Otherwise, send empty list to self
      case _ =>
        self ! MobilityStatusResponse(Vector(), getCurrentTriggerIdOrGenerate)
    }
  }

  private def requestAvailableVehicles(
    vehicleFleets: Seq[ActorRef],
    location: SpaceTime,
    activity: Activity
  ): Future[MobilityStatusResponse] = {
    implicit val executionContext: ExecutionContext = context.system.dispatcher
    Future
      .sequence(
        vehicleFleets.map(
          _ ? MobilityStatusInquiry(
            id,
            location,
            activity,
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
      val correctedCurrentTourMode = choosesModeData.personData.currentTourMode match {
        case Some(mode)
            if availableModes
              .contains(
                mode
              ) && choosesModeData.personData.numberOfReplanningAttempts < beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.maximumNumberOfReplanningAttempts =>
          Some(mode)
        case Some(mode) if availableModes.contains(mode) =>
          Some(WALK)
        case None
            if choosesModeData.personData.numberOfReplanningAttempts >= beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.maximumNumberOfReplanningAttempts =>
          Some(WALK)
        case _ =>
          None
      }

      val bodyStreetVehicle = createBodyStreetVehicle(currentPersonLocation)
      val nextAct = nextActivity(choosesModeData.personData).get
      val departTime = _currentTick.get

      var availablePersonalStreetVehicles =
        correctedCurrentTourMode match {
          case None | Some(CAR | BIKE) =>
            // In these cases, a personal vehicle will be involved
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
        streetVehiclesIntermodalUse: IntermodalUse = Access,
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
          triggerId = getCurrentTriggerIdOrGenerate
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

      val hasRideHail = availableModes.contains(RIDE_HAIL)

      var responsePlaceholders = ChoosesModeResponsePlaceholders()
      var requestId: Option[Int] = None
      // Form and send requests

      correctedCurrentTourMode match {
        case None =>
          if (hasRideHail) {
            responsePlaceholders = makeResponsePlaceholders(
              withRouting = true,
              withRideHail = true,
              withRideHailTransit = !choosesModeData.isWithinTripReplanning,
              emergencyGeoId = emergencyGeoId
            )
            makeRideHailRequest()
            if (!choosesModeData.isWithinTripReplanning) {
              requestId = makeRideHailTransitRoutingRequest(bodyStreetVehicle)
            }
          } else {
            responsePlaceholders = makeResponsePlaceholders(withRouting = true, emergencyGeoId = emergencyGeoId)
            requestId = None
          }
          makeRequestWith(
            withTransit = availableModes.exists(_.isTransit),
            newlyAvailableBeamVehicles.map(_.streetVehicle) :+ bodyStreetVehicle,
            possibleEgressVehicles = dummySharedVehicles
          )
        case Some(WALK) =>
          responsePlaceholders = makeResponsePlaceholders(withRouting = true, emergencyGeoId = emergencyGeoId)
          makeRequestWith(withTransit = false, Vector(bodyStreetVehicle))
        case Some(WALK_TRANSIT) =>
          responsePlaceholders = makeResponsePlaceholders(withRouting = true, emergencyGeoId = emergencyGeoId)
          makeRequestWith(withTransit = true, Vector(bodyStreetVehicle))
        case Some(CAV) =>
          // Request from household the trip legs to put into trip
          householdRef ! CavTripLegsRequest(bodyVehiclePersonId, currentActivity(choosesModeData.personData))
          responsePlaceholders = makeResponsePlaceholders(withPrivateCAV = true, emergencyGeoId = emergencyGeoId)
        case Some(mode @ (CAR | BIKE)) =>
          val maybeLeg = _experiencedBeamPlan.getPlanElements
            .get(_experiencedBeamPlan.getPlanElements.indexOf(nextAct) - 1) match {
            case l: Leg => Some(l)
            case _      => None
          }
          maybeLeg.map(_.getRoute) match {
            case Some(r: NetworkRoute) =>
              val maybeVehicle =
                filterStreetVehiclesForQuery(newlyAvailableBeamVehicles.map(_.streetVehicle), mode).headOption
              maybeVehicle match {
                case Some(vehicle) =>
                  router ! matsimLegToEmbodyRequest(
                    r,
                    vehicle,
                    departTime,
                    mode,
                    beamServices,
                    choosesModeData.currentLocation.loc,
                    nextAct.getCoord,
                    triggerId
                  )
                  responsePlaceholders = makeResponsePlaceholders(withRouting = true, emergencyGeoId = emergencyGeoId)
                case _ =>
                  makeRequestWith(withTransit = false, Vector(bodyStreetVehicle))
                  responsePlaceholders = makeResponsePlaceholders(withRouting = true, emergencyGeoId = emergencyGeoId)
              }
            case _ =>
              makeRequestWith(
                withTransit = false,
                filterStreetVehiclesForQuery(newlyAvailableBeamVehicles.map(_.streetVehicle), mode) :+ bodyStreetVehicle
              )
              responsePlaceholders = makeResponsePlaceholders(withRouting = true, emergencyGeoId = emergencyGeoId)
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
              responsePlaceholders = makeResponsePlaceholders(withRouting = true, emergencyGeoId = emergencyGeoId)
            case (LastTripIndex, Some(currentTourPersonalVehicle)) =>
              // At the end of the tour, only drive home a vehicle that we have also taken away from there.
              makeRequestWith(
                withTransit = true,
                newlyAvailableBeamVehicles
                  .map(_.streetVehicle)
                  .filter(_.id == currentTourPersonalVehicle) :+ bodyStreetVehicle,
                streetVehiclesIntermodalUse = Egress
              )
              responsePlaceholders = makeResponsePlaceholders(withRouting = true, emergencyGeoId = emergencyGeoId)
            case _ =>
              // Reset available vehicles so we don't release our car that we've left during this replanning
              availablePersonalStreetVehicles = Vector()
              makeRequestWith(withTransit = true, Vector(bodyStreetVehicle))
              responsePlaceholders = makeResponsePlaceholders(withRouting = true, emergencyGeoId = emergencyGeoId)
          }
        case Some(RIDE_HAIL | RIDE_HAIL_POOLED) if choosesModeData.isWithinTripReplanning =>
          // Give up on all ride hail after a failure
          responsePlaceholders = makeResponsePlaceholders(withRouting = true, emergencyGeoId = emergencyGeoId)
          makeRequestWith(withTransit = true, Vector(bodyStreetVehicle))
        case Some(RIDE_HAIL | RIDE_HAIL_POOLED) =>
          responsePlaceholders =
            makeResponsePlaceholders(withRouting = true, withRideHail = true, emergencyGeoId = emergencyGeoId)
          makeRequestWith(withTransit = false, Vector(bodyStreetVehicle)) // We need a WALK alternative if RH fails
          makeRideHailRequest()
        case Some(RIDE_HAIL_TRANSIT) if choosesModeData.isWithinTripReplanning =>
          // Give up on ride hail transit after a failure, too complicated, but try regular ride hail again
          responsePlaceholders =
            makeResponsePlaceholders(withRouting = true, withRideHail = true, emergencyGeoId = emergencyGeoId)
          makeRequestWith(withTransit = true, Vector(bodyStreetVehicle))
          makeRideHailRequest()
        case Some(RIDE_HAIL_TRANSIT) =>
          responsePlaceholders = makeResponsePlaceholders(withRideHailTransit = true, emergencyGeoId = emergencyGeoId)
          requestId = makeRideHailTransitRoutingRequest(bodyStreetVehicle)
        case Some(m) =>
          logDebug(m.toString)
      }
      val newPersonData = choosesModeData.copy(
        personData = choosesModeData.personData.copy(currentTourMode = correctedCurrentTourMode),
        availablePersonalStreetVehicles = availablePersonalStreetVehicles,
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
          theRouterResult @ RoutingResponse(_, requestId, _, _, _),
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
          theRouterResult @ RoutingResponse(_, requestId, _, _, _),
          choosesModeData: ChoosesModeData
        ) if choosesModeData.rideHail2TransitRoutingRequestId.contains(requestId) =>
      theRouterResult.itineraries.view.foreach { resp =>
        resp.beamLegs.filter(_.mode == CAR).foreach { leg =>
          routeHistory.rememberRoute(leg.travelPath.linkIds, leg.startTime)
        }
      }

      val driveTransitTrip = theRouterResult.itineraries.find(_.tripClassifier == DRIVE_TRANSIT)
      // If there's a drive-transit trip AND we don't have an error RH2Tr response (due to no desire to use RH) then seek RH on access and egress
      val newPersonData =
        if (
          shouldAttemptRideHail2Transit(
            driveTransitTrip,
            choosesModeData.rideHail2TransitAccessResult
          )
        ) {
          val accessSegment =
            driveTransitTrip.get.legs.view
              .takeWhile(!_.beamLeg.mode.isMassTransit)
              .map(_.beamLeg)
          val egressSegment =
            driveTransitTrip.get.legs.view.reverse.takeWhile(!_.beamLeg.mode.isTransit).reverse.map(_.beamLeg)
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
            rideHail2TransitRoutingResponse = Some(driveTransitTrip.get),
            rideHail2TransitAccessInquiryId = accessId,
            rideHail2TransitEgressInquiryId = egressId,
            rideHail2TransitAccessResult = if (accessId.isEmpty) {
              Some(RideHailResponse.dummyWithError(RideHailNotRequestedError))
            } else {
              None
            },
            rideHail2TransitEgressResult = if (egressId.isEmpty) {
              Some(RideHailResponse.dummyWithError(RideHailNotRequestedError))
            } else {
              None
            }
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
      val parkingRequestIds = makeParkingInquiries(choosesModeData, response.itineraries)
      val newParkingRequestIds = choosesModeData.parkingRequestIds ++ parkingRequestIds
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
          routingFinished = true
        )
      }
      stay() using newData
    case Event(theRideHailResult: RideHailResponse, choosesModeData: ChoosesModeData) =>
      //      println(s"receiving response: ${theRideHailResult}")
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
                veh.vehicleManagerId,
                Some(veh),
                None,
                attributes.valueOfTime,
                getActivityEndTime(nextAct, beamServices) - leg.beamLeg.endTime,
                reserveStall = false,
                triggerId = getCurrentTriggerIdOrGenerate
              ))
            )
          }
      }

    parkingInquiries.foreach { case (_, inquiry) =>
      if (inquiry.isChargingRequestOrEV) chargingNetworkManager ! inquiry
      else parkingManager ! inquiry
    }
    parkingInquiries.map { case (vehicleOnTrip, inquiry) =>
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
          if (legVehicleHasParkingBehavior(embodiedLeg)) splitLegForParking(embodiedLeg)
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

  private def legVehicleHasParkingBehavior(embodiedLeg: EmbodiedBeamLeg) = {
    /* we need to park cars and any shared vehicles */
    embodiedLeg.beamLeg.mode == CAR && dummyRHVehicle.id != embodiedLeg.beamVehicleId ||
    (embodiedLeg.beamLeg.mode == BIKE && beamVehicles.get(embodiedLeg.beamVehicleId).forall(_.isInstanceOf[Token]))
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

  def isRideHailToTransitResponse(response: RoutingResponse): Boolean = {
    response.itineraries.exists(_.vehiclesInTrip.contains(dummyRHVehicle.id))
  }

  def shouldAttemptRideHail2Transit(
    driveTransitTrip: Option[EmbodiedBeamTrip],
    rideHail2TransitResult: Option[RideHailResponse]
  ): Boolean = {
    driveTransitTrip.isDefined && driveTransitTrip.get.legs
      .exists(leg => beamScenario.rideHailTransitModes.contains(leg.beamLeg.mode)) &&
    rideHail2TransitResult.getOrElse(RideHailResponse.DUMMY).error.isEmpty
  }

  def makeRideHailRequestFromBeamLeg(legs: Seq[BeamLeg]): Option[Int] = {
    val inquiry = RideHailRequest(
      RideHailInquiry,
      bodyVehiclePersonId,
      beamServices.geo.wgs2Utm(legs.head.travelPath.startPoint.loc),
      legs.head.startTime,
      beamServices.geo.wgs2Utm(legs.last.travelPath.endPoint.loc),
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

  private def splitLegForParking(leg: EmbodiedBeamLeg) = {
    val theLinkIds = leg.beamLeg.travelPath.linkIds
    val indexFromEnd = Math.min(
      Math.max(
        theLinkIds.reverse
          .map(lengthOfLink)
          .scanLeft(0.0)(_ + _)
          .indexWhere(
            _ > beamServices.beamConfig.beam.agentsim.thresholdForMakingParkingChoiceInMeters
          ),
        1
      ),
      theLinkIds.length - 1
    )
    val indexFromBeg = theLinkIds.length - indexFromEnd
    val firstTravelTimes = leg.beamLeg.travelPath.linkTravelTime.take(indexFromBeg)
    val secondPathLinkIds = theLinkIds.takeRight(indexFromEnd + 1)
    val secondTravelTimes = leg.beamLeg.travelPath.linkTravelTime.takeRight(indexFromEnd + 1)
    val secondDuration = Math.min(math.round(secondTravelTimes.tail.sum.toFloat), leg.beamLeg.duration)
    val firstDuration = leg.beamLeg.duration - secondDuration
    val secondDistance = Math.min(secondPathLinkIds.tail.map(lengthOfLink).sum, leg.beamLeg.travelPath.distanceInM)
    val firstPathEndpoint =
      SpaceTime(
        beamServices.geo
          .coordOfR5Edge(transportNetwork.streetLayer, theLinkIds(math.min(theLinkIds.size - 1, indexFromBeg))),
        leg.beamLeg.startTime + firstDuration
      )
    val secondPath = leg.beamLeg.travelPath.copy(
      linkIds = secondPathLinkIds,
      linkTravelTime = secondTravelTimes,
      startPoint = firstPathEndpoint,
      endPoint =
        leg.beamLeg.travelPath.endPoint.copy(time = (firstPathEndpoint.time + secondTravelTimes.tail.sum).toInt),
      distanceInM = secondDistance
    )
    val firstPath = leg.beamLeg.travelPath.copy(
      linkIds = theLinkIds.take(indexFromBeg),
      linkTravelTime = firstTravelTimes,
      endPoint =
        firstPathEndpoint.copy(time = (leg.beamLeg.travelPath.startPoint.time + firstTravelTimes.tail.sum).toInt),
      distanceInM = leg.beamLeg.travelPath.distanceInM - secondPath.distanceInM
    )
    val firstLeg = leg.copy(
      beamLeg = leg.beamLeg.copy(
        travelPath = firstPath,
        duration = firstDuration
      ),
      unbecomeDriverOnCompletion = false
    )
    val secondLeg = leg.copy(
      beamLeg = leg.beamLeg.copy(
        travelPath = secondPath,
        startTime = firstLeg.beamLeg.startTime + firstLeg.beamLeg.duration,
        duration = secondDuration
      ),
      cost = 0
    )
    assert((firstLeg.cost + secondLeg.cost).equals(leg.cost))
    assert(firstLeg.beamLeg.duration + secondLeg.beamLeg.duration == leg.beamLeg.duration)
    assert(
      Math.abs(
        leg.beamLeg.travelPath.distanceInM - firstLeg.beamLeg.travelPath.distanceInM - secondLeg.beamLeg.travelPath.distanceInM
      ) < 1.0
    )
    Vector(firstLeg, secondLeg)
  }

  def lengthOfLink(linkId: Int): Double = {
    val edge = transportNetwork.streetLayer.edgeStore.getCursor(linkId)
    edge.getLengthM
  }

  def createRideHail2TransitItin(
    rideHail2TransitAccessResult: RideHailResponse,
    rideHail2TransitEgressResult: RideHailResponse,
    driveTransitTrip: EmbodiedBeamTrip
  ): Option[EmbodiedBeamTrip] = {
    if (rideHail2TransitAccessResult.error.isEmpty) {
      val tncAccessLeg: Vector[EmbodiedBeamLeg] =
        rideHail2TransitAccessResult.travelProposal.get.toEmbodiedBeamLegsForCustomer(bodyVehiclePersonId)
      // Replacing drive access leg with TNC changes the travel time.
      val timeToCustomer = rideHail2TransitAccessResult.travelProposal.get.passengerSchedule
        .legsBeforePassengerBoards(bodyVehiclePersonId)
        .map(_.duration)
        .sum
      val extraWaitTimeBuffer = driveTransitTrip.legs.head.beamLeg.endTime - _currentTick.get -
        tncAccessLeg.last.beamLeg.duration - timeToCustomer
      if (extraWaitTimeBuffer < 300) {
        // We filter out all options that don't allow at least 5 minutes of time for unexpected waiting
        None
      } else {
        // Travel time usually decreases, adjust for this but add a buffer to the wait time to account for uncertainty in actual wait time
        val startTimeAdjustment =
          driveTransitTrip.legs.head.beamLeg.endTime - tncAccessLeg.last.beamLeg.duration - timeToCustomer
        val startTimeBufferForWaiting = math.min(
          extraWaitTimeBuffer,
          math.max(300.0, timeToCustomer.toDouble * 1.5)
        ) // tncAccessLeg.head.beamLeg.startTime - _currentTick.get.longValue()
        val accessAndTransit = tncAccessLeg.map(leg =>
          leg.copy(
            leg.beamLeg
              .updateStartTime(startTimeAdjustment - startTimeBufferForWaiting.intValue())
          )
        ) ++ driveTransitTrip.legs.tail
        val fullTrip = if (rideHail2TransitEgressResult.error.isEmpty) {
          accessAndTransit.dropRight(2) ++ rideHail2TransitEgressResult.travelProposal.get
            .toEmbodiedBeamLegsForCustomer(bodyVehiclePersonId)
        } else {
          accessAndTransit.dropRight(1)
        }
        Some(
          EmbodiedBeamTrip(
            EmbodiedBeamLeg.dummyLegAt(
              start = fullTrip.head.beamLeg.startTime,
              vehicleId = body.id,
              isLastLeg = false,
              location = fullTrip.head.beamLeg.travelPath.startPoint.loc,
              mode = WALK,
              vehicleTypeId = body.beamVehicleType.id
            ) +:
            fullTrip :+
            EmbodiedBeamLeg.dummyLegAt(
              start = fullTrip.last.beamLeg.endTime,
              vehicleId = body.id,
              isLastLeg = true,
              location = fullTrip.last.beamLeg.travelPath.endPoint.loc,
              mode = WALK,
              vehicleTypeId = body.beamVehicleType.id
            )
          )
        )
      }
    } else {
      None
    }
  }

  def addParkingCostToItins(
    itineraries: Seq[EmbodiedBeamTrip],
    parkingResponses: Map[VehicleOnTrip, ParkingInquiryResponse]
  ): Seq[EmbodiedBeamTrip] = {
    itineraries.map { itin =>
      itin.tripClassifier match {
        case CAR | DRIVE_TRANSIT | BIKE_TRANSIT | BIKE =>
          //find parking legs (the subsequent leg of the same vehicle)
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
              leg.copy(
                cost = leg.cost + parkingResponses(
                  VehicleOnTrip(leg.beamVehicleId, TripIdentifier(itin))
                ).stall.costInDollars
              )
            } else if (walkLegsAfterParkingWithParkingResponses.contains(leg)) {
              val dist = geo.distUTMInMeters(
                geo.wgs2Utm(leg.beamLeg.travelPath.endPoint.loc),
                walkLegsAfterParkingWithParkingResponses(leg).stall.locationUTM
              )
              val travelTime: Int = (dist / ZonalParkingManager.AveragePersonWalkingSpeed).toInt
              leg.copy(beamLeg = leg.beamLeg.scaleToNewDuration(travelTime))
            } else {
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
      val rideHail2TransitIinerary = createRideHail2TransitItin(
        rideHail2TransitAccessResult,
        rideHail2TransitEgressResult,
        rideHail2TransitRoutingResponse
      )
      val rideHailItinerary = rideHailResult.travelProposal match {
        case Some(travelProposal)
            if travelProposal.timeToCustomer(
              bodyVehiclePersonId
            ) <= beamScenario.beamConfig.beam.agentsim.agents.rideHail.allocationManager.maxWaitingTimeInSec =>
          val origLegs = travelProposal.toEmbodiedBeamLegsForCustomer(bodyVehiclePersonId)
          (travelProposal.poolingInfo match {
            case Some(poolingInfo) if !choosesModeData.personData.currentTourMode.contains(RIDE_HAIL) =>
              val pooledLegs = origLegs.map { origLeg =>
                origLeg.copy(
                  cost = origLeg.cost * poolingInfo.costFactor,
                  isPooledTrip = origLeg.isRideHail,
                  beamLeg = origLeg.beamLeg.scaleLegDuration(poolingInfo.timeFactor)
                )
              }
              Vector(origLegs, EmbodiedBeamLeg.makeLegsConsistent(pooledLegs))
            case _ =>
              Vector(origLegs)
          }).map { partialItin =>
            EmbodiedBeamTrip(
              EmbodiedBeamLeg.dummyLegAt(
                start = _currentTick.get,
                vehicleId = body.id,
                isLastLeg = false,
                location = partialItin.head.beamLeg.travelPath.startPoint.loc,
                mode = WALK,
                vehicleTypeId = body.beamVehicleType.id
              ) +:
              partialItin :+
              EmbodiedBeamLeg.dummyLegAt(
                start = partialItin.last.beamLeg.endTime,
                vehicleId = body.id,
                isLastLeg = true,
                location = partialItin.last.beamLeg.travelPath.endPoint.loc,
                mode = WALK,
                vehicleTypeId = body.beamVehicleType.id
              )
            )
          }
        case _ =>
          Vector()
      }
      val combinedItinerariesForChoice = rideHailItinerary ++ addParkingCostToItins(
        routingResponse.itineraries,
        parkingResponses
      ) ++ rideHail2TransitIinerary.toVector
      //      val test = createRideHail2TransitItin(rideHail2TransitAccessResult, rideHail2TransitEgressResult, routingResponse)

      val availableModes: Seq[BeamMode] = availableModesForPerson(
        matsimPlan.getPerson
      ).filterNot(mode => choosesModeData.excludeModes.contains(mode))

      val filteredItinerariesForChoice = (choosesModeData.personData.currentTourMode match {
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
                trip.tripClassifier == WALK_TRANSIT || trip.tripClassifier == RIDE_HAIL_TRANSIT
              )
          }
        case Some(mode) if mode == WALK_TRANSIT || mode == RIDE_HAIL_TRANSIT =>
          combinedItinerariesForChoice.filter(trip =>
            trip.tripClassifier == WALK_TRANSIT || trip.tripClassifier == RIDE_HAIL_TRANSIT
          )
        case Some(mode) =>
          combinedItinerariesForChoice.filter(_.tripClassifier == mode)
        case _ =>
          combinedItinerariesForChoice
      }).filter(itin => availableModes.contains(itin.tripClassifier))

      val attributesOfIndividual =
        matsimPlan.getPerson.getCustomAttributes
          .get("beam-attributes")
          .asInstanceOf[AttributesOfIndividual]
      val availableAlts = Some(filteredItinerariesForChoice.map(_.tripClassifier).mkString(":"))

      modeChoiceCalculator(
        filteredItinerariesForChoice,
        attributesOfIndividual,
        nextActivity(choosesModeData.personData),
        Some(matsimPlan.getPerson)
      ) match {
        case Some(chosenTrip) =>
          goto(FinishingModeChoice) using choosesModeData.copy(
            pendingChosenTrip = Some(chosenTrip),
            availableAlternatives = availableAlts
          )
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

              goto(FinishingModeChoice) using choosesModeData.copy(
                pendingChosenTrip = Some(expensiveWalkTrip),
                availableAlternatives = availableAlts
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
    val chosenTrip = data.pendingChosenTrip.get
    val (tick, triggerId) = releaseTickAndTriggerId()
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
      _experiencedBeamPlan
        .activities(data.personData.currentActivityIndex)
        .setLinkId(
          Id.createLinkId(
            beamServices.geo.getNearestR5Edge(transportNetwork.streetLayer, origin, 10000)
          )
        )
      _experiencedBeamPlan
        .activities(data.personData.currentActivityIndex + 1)
        .setLinkId(
          Id.createLinkId(
            beamServices.geo.getNearestR5Edge(transportNetwork.streetLayer, destination, 10000)
          )
        )
    }

    eventsManager.processEvent(
      new ModeChoiceEvent(
        tick,
        id,
        chosenTrip.tripClassifier.value,
        data.personData.currentTourMode.map(_.value).getOrElse(""),
        data.expectedMaxUtilityOfLatestChoice.getOrElse[Double](Double.NaN),
        _experiencedBeamPlan
          .activities(data.personData.currentActivityIndex)
          .getLinkId
          .toString,
        data.availableAlternatives.get,
        data.availablePersonalStreetVehicles.nonEmpty,
        chosenTrip.legs.view.map(_.beamLeg.travelPath.distanceInM).sum,
        _experiencedBeamPlan.tourIndexOfElement(nextActivity(data.personData).get),
        chosenTrip
      )
    )

    val (vehiclesUsed, vehiclesNotUsed) = data.availablePersonalStreetVehicles
      .partition(vehicle => chosenTrip.vehiclesInTrip.contains(vehicle.id))

    var isCurrentPersonalVehicleVoided = false
    vehiclesNotUsed.collect { case ActualVehicle(vehicle) =>
      data.personData.currentTourPersonalVehicle.foreach { currentVehicle =>
        if (currentVehicle == vehicle.id) {
          logError(
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
            .orElse(vehiclesUsed.headOption.filter(mustBeDrivenHome).map(_.id))
    )
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

  def makeResponsePlaceholders(
    withRouting: Boolean = false,
    withRideHail: Boolean = false,
    withRideHailTransit: Boolean = false,
    withPrivateCAV: Boolean = false,
    emergencyGeoId: Id[_]
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

  case class LegWithPassengerVehicle(leg: EmbodiedBeamLeg, passengerVehicle: Id[BeamVehicle])

  case class CavTripLegsRequest(person: PersonIdWithActorRef, originActivity: Activity)

  case class CavTripLegsResponse(cavOpt: Option[BeamVehicle], legs: List[EmbodiedBeamLeg])

  def getActivityEndTime(activity: Activity, beamServices: BeamServices): Int = {
    (if (activity.getEndTime.equals(Double.NegativeInfinity))
       Time.parseTime(beamServices.beamConfig.matsim.modules.qsim.endTime)
     else
       activity.getEndTime).toInt
  }

}
