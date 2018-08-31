package beam.agentsim.agents.modalbehaviors

import akka.actor.FSM
import beam.agentsim.Resource.CheckInResource
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents._
import beam.agentsim.agents.household.HouseholdActor.MobilityStatusInquiry.mobilityStatusInquiry
import beam.agentsim.agents.household.HouseholdActor.{
  MobilityStatusResponse,
  ReleaseVehicleReservation
}
import beam.agentsim.agents.modalbehaviors.ChoosesMode._
import beam.agentsim.agents.ridehail.{RideHailInquiry, RideHailRequest, RideHailResponse}
import beam.agentsim.agents.vehicles.AccessErrorCodes.RideHailNotRequestedError
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{VehiclePersonId, _}
import beam.agentsim.events.{ModeChoiceEvent, SpaceTime}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.router.BeamRouter._
import beam.router.Modes
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode._
import beam.router.RoutingModel._
import beam.router.r5.R5RoutingWorker
import beam.utils.plansampling.AvailableModeUtils._
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Leg
import org.matsim.core.population.routes.NetworkRoute
import org.matsim.vehicles.Vehicle
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import java.util.UUID

/**
  * BEAM
  */
trait ChoosesMode {
  this: PersonAgent => // Self type restricts this trait to only mix into a PersonAgent
  val dummyRHVehicle =
    StreetVehicle(
      Id.create("dummyRH", classOf[Vehicle]),
      SpaceTime(0.0, 0.0, 0l),
      CAR,
      asDriver = false
    )
  val bodyVehiclePersonId = VehiclePersonId(bodyId, id, Some(self))

  onTransition {
    case (PerformingActivity | Waiting | WaitingForReservationConfirmation |
        ProcessingNextLegOrStartActivity) -> ChoosingMode =>
      stateData.asInstanceOf[BasePersonData].currentTourMode match {
        case Some(CAR | BIKE | DRIVE_TRANSIT) =>
          // Only need to get available street vehicles from household if our mode requires such a vehicle
          context.parent ! mobilityStatusInquiry(id)
        case None =>
          context.parent ! mobilityStatusInquiry(id)
        case _ =>
          // Otherwise, send empty list to self
          self ! MobilityStatusResponse(Vector())
      }
  }

  when(ChoosingMode)(stateFunction = transform {
    case Event(MobilityStatusResponse(streetVehicles), choosesModeData: ChoosesModeData) =>
      val bodyStreetVehicle = StreetVehicle(
        bodyId,
        SpaceTime(currentActivity(choosesModeData.personData).getCoord, _currentTick.get.toLong),
        WALK,
        asDriver = true
      )
      val nextAct = nextActivity(choosesModeData.personData).right.get
      val departTime = DiscreteTime(_currentTick.get.toInt)

      val availableModes: Seq[BeamMode] = availableModesForPerson(
        beamServices.matsimServices.getScenario.getPopulation.getPersons.get(id)
      )

      val availablePersonalStreetVehicles =
        choosesModeData.personData.currentTourMode match {
          case None | Some(CAR | BIKE) =>
            // In these cases, a personal vehicle will be involved
            streetVehicles.filter(_.asDriver)
          case Some(DRIVE_TRANSIT) =>
            val tour = _experiencedBeamPlan.getTourContaining(nextAct)
            val tripIndex = tour.tripIndexOfElement(nextAct)
            if (tripIndex == 0 || tripIndex == tour.trips.size - 1) {
              streetVehicles.filter(_.asDriver)
            } else {
              Vector()
            }
          case _ =>
            Vector()
        }

      def makeRequestWith(
        transitModes: Vector[BeamMode],
        vehicles: Vector[StreetVehicle],
        streetVehiclesIntermodalUse: IntermodalUse = Access
      ): Unit = {
        router ! RoutingRequest(
          currentActivity(choosesModeData.personData).getCoord,
          nextAct.getCoord,
          departTime,
          Modes.filterForTransit(transitModes),
          vehicles,
          streetVehiclesIntermodalUse,
          mustParkAtEnd = true
        )
      }

      def makeRideHailRequest(): Unit = {
        val inquiry = RideHailRequest(
          RideHailInquiry,
          bodyVehiclePersonId,
          currentActivity(choosesModeData.personData).getCoord,
          departTime,
          nextAct.getCoord
        )
//        println(s"requesting: ${inquiry.requestId}")
        rideHailManager ! inquiry
      }

      def makeRideHailTransitRoutingRequest(bodyStreetVehicle: StreetVehicle): Option[UUID] = {
        //TODO make ride hail wait buffer config param
        val startWithWaitBuffer = 600 + departTime.atTime.toLong
        val currentSpaceTime =
          SpaceTime(currentActivity(choosesModeData.personData).getCoord, startWithWaitBuffer)
        val theRequest = RoutingRequest(
          currentSpaceTime.loc,
          nextAct.getCoord,
          departTime.copy(atTime = startWithWaitBuffer.toInt),
          Vector(TRANSIT),
          Vector(bodyStreetVehicle, dummyRHVehicle.copy(location = currentSpaceTime)),
          AccessAndEgress
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

      // Cache condition variables here to restrict modes to only those available

      val hasRideHail = availableModes.contains(RIDE_HAIL)

      var responsePlaceholders = ChoosesModeResponsePlaceholders()
      var requestId: Option[UUID] = None
      // Form and send requests

      choosesModeData.personData.currentTourMode match {
        case None =>
          if (hasRideHail) {
            responsePlaceholders = makeResponsePlaceholders(
              withRouting = true,
              withRideHail = true,
              withRideHailTransit = true
            )
            makeRideHailRequest()
            requestId = makeRideHailTransitRoutingRequest(bodyStreetVehicle)
          } else {
            responsePlaceholders = makeResponsePlaceholders(withRouting = true)
            requestId = None
          }
          makeRequestWith(Vector(TRANSIT), streetVehicles :+ bodyStreetVehicle)
        case Some(WALK) =>
          responsePlaceholders = makeResponsePlaceholders(withRouting = true)
          makeRequestWith(Vector(), Vector(bodyStreetVehicle))
        case Some(WALK_TRANSIT) =>
          responsePlaceholders = makeResponsePlaceholders(withRouting = true)
          makeRequestWith(Vector(TRANSIT), Vector(bodyStreetVehicle))
        case Some(mode @ (CAR | BIKE)) =>
          responsePlaceholders = makeResponsePlaceholders(withRouting = true)
          val maybeLeg = _experiencedBeamPlan.getPlanElements
            .get(_experiencedBeamPlan.getPlanElements.indexOf(nextAct) - 1) match {
            case l: Leg => Some(l)
            case _      => None
          }
          maybeLeg.map(l => (l, l.getRoute)) match {
            case Some((l, r: NetworkRoute)) =>
              val maybeVehicle =
                filterStreetVehiclesForQuery(streetVehicles, mode).headOption
              maybeVehicle match {
                case Some(vehicle) =>
                  val leg = BeamLeg(
                    departTime.atTime,
                    mode,
                    l.getTravelTime.toLong,
                    BeamPath(
                      (r.getStartLinkId +: r.getLinkIds.asScala :+ r.getEndLinkId)
                        .map(id => id.toString.toInt)
                        .toVector,
                      None,
                      SpaceTime.zero,
                      SpaceTime.zero,
                      r.getDistance
                    )
                  )
                  router ! EmbodyWithCurrentTravelTime(leg, vehicle.id)
                case _ =>
                  makeRequestWith(Vector(), Vector(bodyStreetVehicle))
              }
            case _ =>
              makeRequestWith(
                Vector(),
                filterStreetVehiclesForQuery(streetVehicles, mode) :+ bodyStreetVehicle
              )
          }
        case Some(DRIVE_TRANSIT) =>
          responsePlaceholders = makeResponsePlaceholders(withRouting = true)
          val LastTripIndex = currentTour(choosesModeData.personData).trips.size - 1
          (
            currentTour(choosesModeData.personData).tripIndexOfElement(nextAct),
            choosesModeData.personData.currentTourPersonalVehicle
          ) match {
            case (0, _) if !choosesModeData.isWithinTripReplanning =>
              // We use our car if we are not replanning, otherwise we end up doing a walk transit (catch-all below)
              makeRequestWith(
                Vector(TRANSIT),
                filterStreetVehiclesForQuery(streetVehicles, CAR) :+ bodyStreetVehicle
              )
            case (LastTripIndex, Some(currentTourPersonalVehicleId)) =>
              // At the end of the tour, only drive home a vehicle that we have also taken away from there.
              makeRequestWith(
                Vector(TRANSIT),
                streetVehicles
                  .filter(_.id == currentTourPersonalVehicleId) :+ bodyStreetVehicle,
                streetVehiclesIntermodalUse = Egress
              )
            case _ =>
              makeRequestWith(Vector(TRANSIT), Vector(bodyStreetVehicle))
          }
        case Some(RIDE_HAIL) if choosesModeData.isWithinTripReplanning =>
          // Give up on all ride hail after a failure
          responsePlaceholders = makeResponsePlaceholders(withRouting = true)
          makeRequestWith(Vector(TRANSIT), Vector(bodyStreetVehicle))
        case Some(RIDE_HAIL) =>
          responsePlaceholders = makeResponsePlaceholders(withRideHail = true)
          makeRequestWith(Vector(), Vector(bodyStreetVehicle)) // We need a WALK alternative if RH fails
          makeRideHailRequest()
        case Some(RIDE_HAIL_TRANSIT) if choosesModeData.isWithinTripReplanning =>
          // Give up on ride hail transit after a failure, too complicated, but try regular ride hail again
          responsePlaceholders = makeResponsePlaceholders(withRouting = true, withRideHail = true)
          makeRequestWith(Vector(TRANSIT), Vector(bodyStreetVehicle))
          makeRideHailRequest()
        case Some(RIDE_HAIL_TRANSIT) =>
          responsePlaceholders = makeResponsePlaceholders(withRideHailTransit = true)
          requestId = makeRideHailTransitRoutingRequest(bodyStreetVehicle)
        case Some(m) =>
          logDebug(s"$m: other then expected")
      }
      val newPersonData = choosesModeData.copy(
        availablePersonalStreetVehicles = availablePersonalStreetVehicles,
        routingResponse = responsePlaceholders.routingResponse,
        rideHail2TransitRoutingResponse = responsePlaceholders.rideHail2TransitRoutingResponse,
        rideHail2TransitRoutingRequestId = requestId,
        rideHailResult = responsePlaceholders.rideHailResult,
        rideHail2TransitAccessResult = responsePlaceholders.rideHail2TransitAccessResult,
        rideHail2TransitEgressResult = responsePlaceholders.rideHail2TransitEgressResult
      )
      stay() using newPersonData
    /*
     * Receive and store data needed for choice.
     */
    case Event(
        theRouterResult @ RoutingResponse(_, Some(requestId)),
        choosesModeData: ChoosesModeData
        ) if choosesModeData.rideHail2TransitRoutingRequestId.contains(requestId) =>
      val driveTransitTrip =
        theRouterResult.itineraries
          .dropWhile(_.tripClassifier != DRIVE_TRANSIT)
          .headOption
      // If there's a drive-transit trip AND we don't have an error RH2Tr response (due to no desire to use RH) then seek RH on access and egress
      val newPersonData =
        if (shouldAttemptRideHail2Transit(
              driveTransitTrip,
              choosesModeData.rideHail2TransitAccessResult
            )) {
          val accessSegment =
            driveTransitTrip.get.legs
              .takeWhile(!_.beamLeg.mode.isMassTransit)
              .map(_.beamLeg)
          val egressSegment = driveTransitTrip.get.legs
            .dropWhile(!_.beamLeg.mode.isMassTransit)
            .dropWhile(_.beamLeg.mode.isMassTransit)
            .map(_.beamLeg)
          //TODO replace hard code number here with parameter
          val accessId =
            if (accessSegment.map(_.travelPath.distanceInM).sum > 0) {
              makeRideHailRequestFromBeamLeg(accessSegment)
            } else {
              None
            }
          val egressId =
            if (egressSegment.map(_.travelPath.distanceInM).sum > 0) {
              makeRideHailRequestFromBeamLeg(egressSegment)
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
            rideHail2TransitAccessResult =
              Some(RideHailResponse.dummyWithError(RideHailNotRequestedError)),
            rideHail2TransitEgressResult =
              Some(RideHailResponse.dummyWithError(RideHailNotRequestedError))
          )
        }
      stay() using newPersonData
    case Event(theRouterResult: RoutingResponse, choosesModeData: ChoosesModeData) =>
      stay() using choosesModeData.copy(routingResponse = Some(theRouterResult))
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

  } using completeChoiceIfReady)

  def isRideHailToTransitResponse(response: RoutingResponse): Boolean = {
    response.itineraries.exists(_.vehiclesInTrip.contains(dummyRHVehicle.id))
  }

  def shouldAttemptRideHail2Transit(
    driveTransitTrip: Option[EmbodiedBeamTrip],
    rideHail2TransitResult: Option[RideHailResponse]
  ): Boolean = {
    driveTransitTrip.isDefined && driveTransitTrip.get.legs
      .exists(_.beamLeg.mode.isMassTransit) &&
    rideHail2TransitResult.getOrElse(RideHailResponse.DUMMY).error.isEmpty
  }

  def makeRideHailRequestFromBeamLeg(legs: IndexedSeq[BeamLeg]): Option[UUID] = {
    val inquiry = RideHailRequest(
      RideHailInquiry,
      bodyVehiclePersonId,
      beamServices.geo.wgs2Utm(legs.head.travelPath.startPoint.loc),
      DiscreteTime(legs.head.startTime.toInt),
      beamServices.geo.wgs2Utm(legs.last.travelPath.endPoint.loc)
    )
//    println(s"requesting: ${inquiry.requestId}")
    rideHailManager ! inquiry
    Some(inquiry.requestId)
  }

  case object FinishingModeChoice extends BeamAgentState

  def createRideHail2TransitItin(
    rideHail2TransitAccessResult: RideHailResponse,
    rideHail2TransitEgressResult: RideHailResponse,
    driveTransitTrip: EmbodiedBeamTrip
  ): Option[EmbodiedBeamTrip] = {
    if (rideHail2TransitAccessResult.error.isEmpty) {
      val tncAccessLeg =
        rideHail2TransitAccessResult.travelProposal.head.responseRideHail2Dest.itineraries.head.legs
          .dropRight(1)
      // Replacing drive access leg with TNC changes the travel time.
      val extraWaitTimeBuffer = driveTransitTrip.legs.head.beamLeg.endTime - _currentTick.get.toInt -
      tncAccessLeg.last.beamLeg.duration - rideHail2TransitAccessResult.travelProposal.get.timeToCustomer.toInt
      if (extraWaitTimeBuffer < 300) {
        // We filter out all options that don't allow at least 5 minutes of time for unexpected waiting
        None
      } else {
        // Travel time usually decreases, adjust for this but add a buffer to the wait time to account for uncertainty in actual wait time
        val startTimeAdjustment = driveTransitTrip.legs.head.beamLeg.endTime - tncAccessLeg.last.beamLeg.duration - rideHail2TransitAccessResult.travelProposal.get.timeToCustomer.toInt
        val startTimeBufferForWaiting = math.min(
          extraWaitTimeBuffer,
          math.max(300.0, rideHail2TransitAccessResult.travelProposal.head.timeToCustomer * 1.5)
        ) // tncAccessLeg.head.beamLeg.startTime - _currentTick.get.longValue()
        val accessAndTransit = tncAccessLeg.map(
          leg =>
            leg.copy(
              leg.beamLeg
                .updateStartTime(startTimeAdjustment - startTimeBufferForWaiting.longValue())
          )
        ) ++ driveTransitTrip.legs.tail
        val fullTrip = if (rideHail2TransitEgressResult.error.isEmpty) {
          accessAndTransit.dropRight(1) ++ rideHail2TransitEgressResult.travelProposal.head.responseRideHail2Dest.itineraries.head.legs.tail
        } else {
          accessAndTransit
        }
        Some(EmbodiedBeamTrip(fullTrip))
      }
    } else {
      None
    }
  }

  def completeChoiceIfReady: PartialFunction[State, State] = {
    case FSM.State(
        _,
        choosesModeData @ ChoosesModeData(
          personData,
          None,
          Some(routingResponse),
          Some(rideHailResult),
          Some(rideHail2TransitRoutingResponse),
          _,
          Some(rideHail2TransitAccessResult),
          _,
          Some(rideHail2TransitEgressResult),
          _,
          _,
          _,
          _
        ),
        _,
        _,
        _
        ) =>
      val nextAct = nextActivity(choosesModeData.personData).right.get
      val rideHail2TransitIinerary = createRideHail2TransitItin(
        rideHail2TransitAccessResult,
        rideHail2TransitEgressResult,
        rideHail2TransitRoutingResponse
      )
      val rideHailItinerary = if (rideHailResult.travelProposal.isDefined) {
        rideHailResult.travelProposal.get.responseRideHail2Dest.itineraries
      } else {
        Vector()
      }
      val combinedItinerariesForChoice = rideHailItinerary ++ routingResponse.itineraries ++ rideHail2TransitIinerary.toVector
      //      val test = createRideHail2TransitItin(rideHail2TransitAccessResult, rideHail2TransitEgressResult, routingResponse)
      val filteredItinerariesForChoice = personData.currentTourMode match {
        case Some(DRIVE_TRANSIT) =>
          val LastTripIndex = currentTour(choosesModeData.personData).trips.size - 1
          (
            currentTour(choosesModeData.personData).tripIndexOfElement(nextAct),
            personData.hasDeparted
          ) match {
            case (0 | LastTripIndex, false) =>
              combinedItinerariesForChoice.filter(_.tripClassifier == DRIVE_TRANSIT)
            case _ =>
              combinedItinerariesForChoice.filter(
                trip =>
                  trip.tripClassifier == WALK_TRANSIT || trip.tripClassifier == RIDE_HAIL_TRANSIT
              )
          }
        case Some(mode) if mode == WALK_TRANSIT || mode == RIDE_HAIL_TRANSIT =>
          combinedItinerariesForChoice.filter(
            trip => trip.tripClassifier == WALK_TRANSIT || trip.tripClassifier == RIDE_HAIL_TRANSIT
          )
        case Some(mode) =>
          combinedItinerariesForChoice.filter(_.tripClassifier == mode)
        case _ =>
          combinedItinerariesForChoice
      }

      modeChoiceCalculator(filteredItinerariesForChoice) match {
        case Some(chosenTrip) =>
          goto(FinishingModeChoice) using choosesModeData.copy(pendingChosenTrip = Some(chosenTrip))
        case None =>
          // Bad things happen but we want them to continue their day, so we signal to downstream that trip should be made to be expensive
          val originalWalkTripLeg =
            routingResponse.itineraries.find(_.tripClassifier == WALK) match {
              case Some(originalWalkTrip) =>
                originalWalkTrip.legs.head
              case None =>
                R5RoutingWorker
                  .createBushwackingTrip(
                    currentActivity(choosesModeData.personData).getCoord,
                    nextAct.getCoord,
                    _currentTick.get.toInt,
                    bodyId,
                    beamServices
                  )
                  .legs
                  .head
            }
          val expensiveWalkTrip = EmbodiedBeamTrip(
            Vector(originalWalkTripLeg.copy(cost = BigDecimal(100.0)))
          )

          goto(FinishingModeChoice) using choosesModeData.copy(
            pendingChosenTrip = Some(expensiveWalkTrip)
          )
      }
  }

  when(FinishingModeChoice, stateTimeout = Duration.Zero) {
    case Event(StateTimeout, data: ChoosesModeData) =>
      val chosenTrip = data.pendingChosenTrip.get
      val (tick, triggerId) = releaseTickAndTriggerId()
      // Write start and end links of chosen route into Activities.
      // We don't check yet whether the incoming and outgoing routes agree on the link an Activity is on.
      // Our aim should be that every transition from a link to another link be accounted for.
      val links = chosenTrip.legs.flatMap(l => l.beamLeg.travelPath.linkIds)
      if (links.nonEmpty) {
        _experiencedBeamPlan
          .activities(data.personData.currentActivityIndex)
          .setLinkId(Id.createLinkId(links.head))
        _experiencedBeamPlan
          .activities(data.personData.currentActivityIndex + 1)
          .setLinkId(Id.createLinkId(links.last))
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

      def availableAlternatives = {
        val theModes =
          data.routingResponse.get.itineraries.map(_.tripClassifier).distinct
        if (data.rideHailResult.isDefined && data.rideHailResult.get.error.isEmpty) {
          theModes :+ RIDE_HAIL
        } else {
          theModes
        }
      }

      eventsManager.processEvent(
        new ModeChoiceEvent(
          tick,
          id,
          chosenTrip.tripClassifier.value,
          data.expectedMaxUtilityOfLatestChoice.getOrElse[Double](Double.NaN),
          _experiencedBeamPlan
            .activities(data.personData.currentActivityIndex)
            .getLinkId
            .toString,
          availableAlternatives.mkString(":"),
          data.availablePersonalStreetVehicles.nonEmpty,
          chosenTrip.legs.map(_.beamLeg.travelPath.distanceInM).sum,
          _experiencedBeamPlan.tourIndexOfElement(nextActivity(data.personData).right.get),
          chosenTrip
        )
      )

      val personalVehicleUsed = data.availablePersonalStreetVehicles
        .map(_.id)
        .intersect(chosenTrip.vehiclesInTrip)
        .headOption

      val availablePersonalStreetVehicles = if (personalVehicleUsed.nonEmpty) {
        data.availablePersonalStreetVehicles.filterNot(_.id == personalVehicleUsed.get)
      } else data.availablePersonalStreetVehicles

      availablePersonalStreetVehicles.foreach { veh =>
        context.parent ! ReleaseVehicleReservation(id, veh.id)
        context.parent ! CheckInResource(veh.id, None)
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
          data.personData.currentTourPersonalVehicle.orElse(personalVehicleUsed)
      )
  }
}

object ChoosesMode {

  case class ChoosesModeData(
    personData: BasePersonData,
    pendingChosenTrip: Option[EmbodiedBeamTrip] = None,
    routingResponse: Option[RoutingResponse] = None,
    rideHailResult: Option[RideHailResponse] = None,
    rideHail2TransitRoutingResponse: Option[EmbodiedBeamTrip] = None,
    rideHail2TransitRoutingRequestId: Option[UUID] = None,
    rideHail2TransitAccessResult: Option[RideHailResponse] = None,
    rideHail2TransitAccessInquiryId: Option[UUID] = None,
    rideHail2TransitEgressResult: Option[RideHailResponse] = None,
    rideHail2TransitEgressInquiryId: Option[UUID] = None,
    availablePersonalStreetVehicles: Vector[StreetVehicle] = Vector(),
    expectedMaxUtilityOfLatestChoice: Option[Double] = None,
    isWithinTripReplanning: Boolean = false
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
        personData =
          personData.copy(currentLegPassengerScheduleIndex = currentLegPassengerScheduleIndex)
      )
    override def hasParkingBehaviors: Boolean = true
  }

  case class ChoosesModeResponsePlaceholders(
    routingResponse: Option[RoutingResponse] = None,
    rideHailResult: Option[RideHailResponse] = None,
    rideHail2TransitRoutingResponse: Option[EmbodiedBeamTrip] = None,
    rideHail2TransitAccessResult: Option[RideHailResponse] = None,
    rideHail2TransitEgressResult: Option[RideHailResponse] = None
  )

  def makeResponsePlaceholders(
    withRouting: Boolean = false,
    withRideHail: Boolean = false,
    withRideHailTransit: Boolean = false
  ): ChoosesModeResponsePlaceholders = {
    ChoosesModeResponsePlaceholders(
      routingResponse = if (withRouting) {
        None
      } else {
        Some(RoutingResponse(Vector()))
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
      }
    )
  }

  case class LegWithPassengerVehicle(leg: EmbodiedBeamLeg, passengerVehicle: Id[Vehicle])

}
