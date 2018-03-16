package beam.agentsim.agents.modalBehaviors

import akka.actor.FSM.Failure
import akka.actor.{ActorRef, FSM}
import beam.agentsim.Resource.CheckInResource
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.rideHail.RideHailingManager.{ReserveRide, RideHailingInquiry, RideHailingInquiryResponse}
import beam.agentsim.agents.TriggerUtils._
import beam.agentsim.agents._
import beam.agentsim.agents.household.HouseholdActor.MobilityStatusInquiry.mobilityStatusInquiry
import beam.agentsim.agents.household.HouseholdActor.{MobilityStatusReponse, ReleaseVehicleReservation}
import beam.agentsim.agents.modalBehaviors.ChoosesMode.{ChoosesModeData, LegWithPassengerVehicle, WaitingForReservationConfirmationData}
import beam.agentsim.agents.modalBehaviors.DrivesVehicle.NotifyLegStartTrigger
import beam.agentsim.agents.planning.Strategy.ModeChoiceStrategy
import beam.agentsim.agents.rideHail.{RideHailingAgent, RideHailingManager}
import beam.agentsim.agents.vehicles.AccessErrorCodes.RideHailNotRequestedError
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{VehiclePersonId, VehicleStack, _}
import beam.agentsim.events.{ModeChoiceEvent, SpaceTime}
import beam.agentsim.scheduler.TriggerWithId
import beam.router.BeamRouter.{EmbodyWithCurrentTravelTime, RoutingRequest, RoutingResponse}
import beam.router.Modes
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode._
import beam.router.RoutingModel._
import com.conveyal.r5.profile.StreetMode
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.{Leg, Person}
import org.matsim.core.population.routes.NetworkRoute
import org.matsim.vehicles.Vehicle

import scala.collection.JavaConverters._


/**
  * BEAM
  */
trait ChoosesMode {
  this: PersonAgent => // Self type restricts this trait to only mix into a PersonAgent

  onTransition {
    case PerformingActivity -> ChoosingMode =>
      val modeChoiceStrategy = _experiencedBeamPlan.getStrategy(nextActivity.right.get, classOf[ModeChoiceStrategy]).asInstanceOf[Option[ModeChoiceStrategy]]
      modeChoiceStrategy match {
        case Some(ModeChoiceStrategy(mode)) if mode == CAR || mode == BIKE || mode == DRIVE_TRANSIT =>
          // Only need to get available street vehicles from household if our mode requires such a vehicle
          context.parent ! mobilityStatusInquiry(id)
        case None =>
          context.parent ! mobilityStatusInquiry(id)
        case _ =>
          // Otherwise, send empty list to self
          self ! MobilityStatusReponse(Vector())
      }
  }

  when(ChoosingMode) ( transform {
    case Event(MobilityStatusReponse(streetVehicles), info @ BeamAgentInfo(_ , choosesModeData: ChoosesModeData,_,_,_,_)) =>
      val bodyStreetVehicle = StreetVehicle(bodyId, SpaceTime(currentActivity.getCoord, _currentTick.get.toLong),
        WALK, asDriver = true)
      var availablePersonalStreetVehicles = streetVehicles.filter(_.asDriver)
      var rideHailingResult = choosesModeData.rideHailingResult
      val nextAct = nextActivity.right.get
      val departTime = DiscreteTime(_currentTick.get.toInt)

      val maybeLeg = _experiencedBeamPlan.getPlanElements.get(_experiencedBeamPlan.getPlanElements.indexOf(nextAct)-1) match {
        case l: Leg => Some(l)
        case _ => None
      }
      val modeChoiceStrategy = maybeLeg.map(l => ModeChoiceStrategy(BeamMode.withValue(l.getMode)))
      modeChoiceStrategy match {
        case Some(ModeChoiceStrategy(mode)) if mode == CAR || mode == BIKE || mode == DRIVE_TRANSIT =>
          // In these cases, a personal vehicle will be involved
          availablePersonalStreetVehicles = streetVehicles.filter(_.asDriver)
        case None =>
          availablePersonalStreetVehicles = streetVehicles.filter(_.asDriver)
        case Some(ModeChoiceStrategy(mode)) if mode == DRIVE_TRANSIT =>
          val tour = _experiencedBeamPlan.getTourContaining(nextAct)
          val tripIndex = tour.tripIndexOfElement(nextAct)
          if (tripIndex == 0 || tripIndex == tour.trips.size - 1) {
            availablePersonalStreetVehicles = streetVehicles.filter(_.asDriver)
          } else {
            availablePersonalStreetVehicles = Vector()
          }
        case _ =>
          availablePersonalStreetVehicles = Vector()
      }

      // Mark rideHailingResult as None if we need to request a new one, or fake a result if we don't need to make a request
      modeChoiceStrategy match {
        case Some(ModeChoiceStrategy(mode)) if mode == RIDE_HAIL =>
          rideHailingResult = None
        case None =>
          rideHailingResult = None
        case _ =>
          rideHailingResult = Some(RideHailingInquiryResponse(Id.create[RideHailingInquiry]("NA", classOf[RideHailingInquiry]), Vector(), Some(RideHailNotRequestedError)))
      }

      def makeRequestWith(transitModes: Vector[BeamMode], vehicles: Vector[StreetVehicle], streetVehiclesAsAccess: Boolean = true): Unit = {
        router ! RoutingRequest(currentActivity.getCoord, nextAct.getCoord, departTime, Modes.filterForTransit(transitModes), vehicles, streetVehiclesAsAccess)
      }

      def makeRideHailRequest(): Unit = {
        rideHailingManager ! RideHailingInquiry(RideHailingManager.nextRideHailingInquiryId, id, currentActivity.getCoord, departTime, nextAct.getCoord)
      }

      def filterStreetVehiclesForQuery(streetVehicles: Vector[StreetVehicle], byMode: BeamMode): Vector[StreetVehicle] = {
        currentTourPersonalVehicle match {
          case Some(personalVeh) =>
            // We already have a vehicle we're using on this tour, so filter down to that
            streetVehicles.filter(_.id == personalVeh)
          case None =>
            // Otherwise, filter by mode
            streetVehicles.filter(_.mode == byMode)
        }
      }

      // Form and send requests
      modeChoiceStrategy match {
        case None =>
          makeRequestWith(Vector(TRANSIT), streetVehicles :+ bodyStreetVehicle)
          makeRideHailRequest()
        case Some(ModeChoiceStrategy(mode)) if mode == WALK =>
          makeRequestWith(Vector(), Vector(bodyStreetVehicle))
        case Some(ModeChoiceStrategy(mode)) if mode == WALK_TRANSIT =>
          makeRequestWith(Vector(TRANSIT), Vector(bodyStreetVehicle))
        case Some(ModeChoiceStrategy(mode)) if mode == CAR || mode == BIKE =>
          maybeLeg.map(l => (l, l.getRoute)) match {
            case Some((l, r: NetworkRoute)) =>
              val maybeVehicle = filterStreetVehiclesForQuery(streetVehicles, mode).headOption
              maybeVehicle match {
                case Some(vehicle) =>
                  val leg = BeamLeg(departTime.atTime, mode, l.getTravelTime.toLong, BeamPath((r.getStartLinkId +: r.getLinkIds.asScala :+ r.getEndLinkId).map(id => id.toString.toInt).toVector, None, SpaceTime.zero, SpaceTime.zero, 0.0))
                  router ! EmbodyWithCurrentTravelTime(leg, vehicle.id)
                case _ =>
                  makeRequestWith(Vector(), filterStreetVehiclesForQuery(streetVehicles, mode) :+ bodyStreetVehicle)
              }
            case _ =>
              makeRequestWith(Vector(), filterStreetVehiclesForQuery(streetVehicles, mode) :+ bodyStreetVehicle)
          }
        case Some(ModeChoiceStrategy(mode)) if mode == DRIVE_TRANSIT =>
          currentTour.tripIndexOfElement(nextAct) match {
            case ind if ind == 0 =>
              makeRequestWith(Vector(TRANSIT), filterStreetVehiclesForQuery(streetVehicles, CAR) :+ bodyStreetVehicle)
            case ind if ind == currentTour.trips.size - 1 =>
              makeRequestWith(Vector(TRANSIT), filterStreetVehiclesForQuery(streetVehicles, CAR) :+ bodyStreetVehicle, streetVehiclesAsAccess = false)
            case _ =>
              makeRequestWith(Vector(TRANSIT), Vector(bodyStreetVehicle))
          }
        case Some(ModeChoiceStrategy(mode)) if mode == RIDE_HAIL =>
          makeRequestWith(Vector(), Vector(bodyStreetVehicle)) // We need a WALK alternative if RH fails
          makeRideHailRequest()
      }
      val newPersonData = choosesModeData.copy(availablePersonalStreetVehicles = availablePersonalStreetVehicles, rideHailingResult = rideHailingResult)
      stay() using info.copy(data = newPersonData)
    /*
     * Receive and store data needed for choice.
     */
    case Event(theRouterResult: RoutingResponse, info @ BeamAgentInfo(_ , choosesModeData: ChoosesModeData,_,_,_,_)) =>
      val newPersonData = choosesModeData.copy(routingResponse = Some(theRouterResult))
      stay() using info.copy(data = newPersonData)
    case Event(theRideHailingResult: RideHailingInquiryResponse, info @ BeamAgentInfo(_ , choosesModeData: ChoosesModeData,_,_,_,_)) =>
      val newPersonData = choosesModeData.copy(rideHailingResult = Some(theRideHailingResult))
      stay() using info.copy(data = newPersonData)

    case Event(TriggerWithId(NotifyLegStartTrigger(tick, beamLeg), theTriggerId), _) =>
      // We've received this leg too early...
      stash()
      stay()
  } using completeChoiceIfReady)

  when(WaitingForReservationConfirmation) (transform { transform {
    case Event(response@ReservationResponse(requestId, _), info @ BeamAgentInfo(_ , wfrcData @ WaitingForReservationConfirmationData(pendingReservationConfirmation, awaitingReservationConfirmation, choosesModeData),_,_,_,_)) =>
      stay() using info.copy(data = wfrcData.copy(pendingReservationConfirmation = pendingReservationConfirmation - requestId, awaitingReservationConfirmation = awaitingReservationConfirmation + (requestId -> (sender(), response))))
    case Event(TriggerWithId(NotifyLegStartTrigger(tick, beamLeg),theTriggerId),_) =>
      // We've received this leg too early...
      stash()
      stay()
  } using finalizeReservationsIfReady } using completeChoiceIfReady)

  def finalizeReservationsIfReady: PartialFunction[State, State] = {
    case s@FSM.State(stateName, info@BeamAgentInfo(_, wfrcData@WaitingForReservationConfirmationData(pendingReservationConfirmation, awaitingReservationConfirmation, choosesModeData), _, _, triggersToSchedule, _), timeout, stopReason, replies)
      if pendingReservationConfirmation.isEmpty =>
      if (awaitingReservationConfirmation.values.forall(_._2.response.isRight)) {
        val triggers = awaitingReservationConfirmation.flatMap(_._2._2.response.right.get.triggersToSchedule)
        log.debug("scheduling triggers from reservation responses: {}", triggers)
        goto(Waiting) using info.copy(data = choosesModeData, triggersToSchedule = triggers.toVector ++ triggersToSchedule)
      } else {
        val firstErrorResponse = awaitingReservationConfirmation.values.filter(_._2.response.isLeft).head._2.response.left.get
        var rideHailingResult = choosesModeData.rideHailingResult
        var routingResponse = choosesModeData.routingResponse
        choosesModeData.pendingChosenTrip.get.tripClassifier match {
          case RIDE_HAIL =>
            rideHailingResult = Some(rideHailingResult.get.copy(proposals = Vector(), error = Some(firstErrorResponse)))
          case _ =>
            routingResponse = Some(routingResponse.get.copy(itineraries = routingResponse.get.itineraries.diff(Seq
            (choosesModeData.pendingChosenTrip.get))))
        }
        if (choosesModeData.routingResponse.get.itineraries.isEmpty & choosesModeData.rideHailingResult.get.error.isDefined) {
          // RideUnavailableError is defined for RHM and the trips are empty, but we don't check
          // if more agents could be hailed.
          stop(Failure(firstErrorResponse.errorCode.toString))
        } else {
          cancelTrip(stateData.data.asInstanceOf[WaitingForReservationConfirmationData].choosesModeData.pendingChosenTrip.get.legs, _currentVehicle)
          goto(ChoosingMode) using info.copy(
            data = choosesModeData.copy(pendingChosenTrip = None, rideHailingResult = rideHailingResult, routingResponse = routingResponse),
            triggersToSchedule = Vector()
          )
        }
      }

  }

  def completeChoiceIfReady: PartialFunction[State, State] = {
    case s @ FSM.State(stateName, info @ BeamAgentInfo(_ , choosesModeData @ ChoosesModeData(None, Some(routingResponse), Some(rideHailingResult), _, _),_,_,_,_), timeout, stopReason, replies) =>
      val modeAlreadyDefined = _experiencedBeamPlan.getStrategy(nextActivity.right.get, classOf[ModeChoiceStrategy]).isDefined
      var predefinedMode: Option[BeamMode] = None
      var combinedItinerariesForChoice = rideHailingResult.proposals.flatMap(x => x.responseRideHailing2Dest.itineraries) ++ routingResponse.itineraries

      if (modeAlreadyDefined) {
        predefinedMode = Some(_experiencedBeamPlan.getStrategy(nextActivity.right.get, classOf[ModeChoiceStrategy]).get.asInstanceOf[ModeChoiceStrategy].mode)
        if (predefinedMode.get != WALK) {
          val itinsWithoutWalk = if (predefinedMode.get == DRIVE_TRANSIT) {
            combinedItinerariesForChoice.filter(itin => itin.tripClassifier == CAR || itin.tripClassifier == DRIVE_TRANSIT)
          } else {
            combinedItinerariesForChoice.filter(_.tripClassifier != WALK)
          }
          if (itinsWithoutWalk.nonEmpty) combinedItinerariesForChoice = itinsWithoutWalk
        }
      }

      modeChoiceCalculator(combinedItinerariesForChoice) match{
        case Some(chosenTrip) =>
          if (chosenTrip.requiresReservationConfirmation) {
            val awaitingReservationConfirmation = sendReservationRequests(chosenTrip, choosesModeData)
            goto(WaitingForReservationConfirmation) using info.copy(data = WaitingForReservationConfirmationData(pendingReservationConfirmation = awaitingReservationConfirmation, awaitingReservationConfirmation = Map(), choosesModeData.copy(pendingChosenTrip = Some(chosenTrip))))
          } else {
            goto(Waiting) using info.copy(data = choosesModeData.copy(pendingChosenTrip = Some(chosenTrip)))
          }
        case None =>
          // Bad things happen but we want them to continue their day, so we signal to downstream that trip should be made to be expensive
          goto(Waiting) using info.copy(data = choosesModeData.copy(pendingChosenTrip = None))
    }
  }

  def sendReservationRequests(chosenTrip: EmbodiedBeamTrip, choosesModeData: ChoosesModeData) = {
    var inferredVehicle: VehicleStack = VehicleStack()
    var exitNextVehicle = false
    var legsWithPassengerVehicle: Vector[LegWithPassengerVehicle] = Vector()
    val rideHailingLeg = RideHailingAgent.getRideHailingTrip(chosenTrip)
    var awaitingReservationConfirmation = Set[Id[ReservationRequest]]()
    if (rideHailingLeg.nonEmpty) {
      val departAt = DiscreteTime(rideHailingLeg.head.beamLeg.startTime.toInt)
      val rideHailingId = Id.create(choosesModeData.rideHailingResult.get.inquiryId.toString, classOf[ReservationRequest])
      rideHailingManager ! ReserveRide(choosesModeData.rideHailingResult.get.inquiryId, VehiclePersonId
      (bodyId, id), currentActivity.getCoord, departAt, nextActivity.right.get.getCoord)
      awaitingReservationConfirmation = awaitingReservationConfirmation + rideHailingId
    } else {
      var prevLeg = chosenTrip.legs.head
      for (leg <- chosenTrip.legs) {
        if (exitNextVehicle || (!prevLeg.asDriver && leg.beamVehicleId != prevLeg.beamVehicleId)) inferredVehicle =
          inferredVehicle.pop()
        //        if (exitNextVehicle) inferredVehicle = inferredVehicle.pop()

        if (inferredVehicle.nestedVehicles.nonEmpty) {
          val passengerVeh: Id[Vehicle] = if (inferredVehicle.outermostVehicle() == leg.beamVehicleId) {
            if (inferredVehicle.nestedVehicles.size < 2) {
              // In this case, we are changing into a WALK leg
              Id.create("dummy", classOf[Vehicle])
            } else {
              inferredVehicle.penultimateVehicle()
            }
          } else {
            inferredVehicle.outermostVehicle()
          }
          legsWithPassengerVehicle = legsWithPassengerVehicle :+ LegWithPassengerVehicle(leg, passengerVeh)
        }
        inferredVehicle = inferredVehicle.pushIfNew(leg.beamVehicleId)
        exitNextVehicle = leg.asDriver && leg.unbecomeDriverOnCompletion
        prevLeg = leg
      }
      val ungroupedLegs = legsWithPassengerVehicle.filter(_.leg.beamLeg.mode.isTransit()).toList
      var runningVehId = ungroupedLegs.head.leg.beamVehicleId
      var groupedLegs = List[List[LegWithPassengerVehicle]]()
      var currentSegmentList = List[LegWithPassengerVehicle]()
      ungroupedLegs.foreach { legwithpass =>
        if (legwithpass.leg.beamVehicleId == runningVehId) {
          currentSegmentList = currentSegmentList :+ legwithpass
        } else {
          groupedLegs = groupedLegs :+ currentSegmentList
          currentSegmentList = List(legwithpass)
          runningVehId = legwithpass.leg.beamVehicleId
        }
        groupedLegs = groupedLegs.slice(0, groupedLegs.size - 1) :+ currentSegmentList
      }
      if (groupedLegs.nonEmpty) {
        groupedLegs.foreach { legSegment =>
          val legs = legSegment.sortBy(_.leg.beamLeg.startTime)
          val vehId = legSegment.head.leg.beamVehicleId
          val resRequest = ReservationRequestWithVehicle(new ReservationRequest(legs.head.leg.beamLeg, legs.last.leg
            .beamLeg, VehiclePersonId(legs.head.passengerVehicle, id)), vehId)
          TransitDriverAgent.selectByVehicleId(vehId) ! resRequest
          awaitingReservationConfirmation = awaitingReservationConfirmation + resRequest.request.requestId
        }
      }
    }
    awaitingReservationConfirmation
  }


  onTransition {
    case ChoosingMode -> Waiting =>
      unstashAll()
      val chosenTrip = nextStateData.data.asInstanceOf[ChoosesModeData].pendingChosenTrip match {
        case Some(pendingChosenTrip) =>
          pendingChosenTrip
        case None =>
          createExpensiveWalkTrip(nextStateData.data.asInstanceOf[ChoosesModeData])
      }
      scheduleDepartureWithValidatedTrip(chosenTrip, nextStateData.data.asInstanceOf[ChoosesModeData])
    case WaitingForReservationConfirmation -> Waiting =>
      // Schedule triggers contained in reservation confirmation
      nextStateData.triggersToSchedule.foreach(scheduler ! _)
      unstashAll()
      val chosenTrip = nextStateData.data.asInstanceOf[ChoosesModeData].pendingChosenTrip match {
        case Some(pendingChosenTrip) =>
          pendingChosenTrip
        case None =>
          createExpensiveWalkTrip(nextStateData.data.asInstanceOf[ChoosesModeData])
      }
      scheduleDepartureWithValidatedTrip(chosenTrip, nextStateData.data.asInstanceOf[ChoosesModeData])
  }

  def createExpensiveWalkTrip(choosesModeData: ChoosesModeData): EmbodiedBeamTrip = {
    val originalWalkTripLeg = choosesModeData.routingResponse.get.itineraries.filter(_.tripClassifier == WALK).head.legs.head
    EmbodiedBeamTrip(Vector(originalWalkTripLeg.copy(cost = BigDecimal(100.0))))
  }

  def scheduleDepartureWithValidatedTrip(chosenTrip: EmbodiedBeamTrip, choosesModeData: ChoosesModeData) = {
    val (tick, theTriggerId) = releaseTickAndTriggerId()
    var availablePersonalStreetVehicles = choosesModeData.availablePersonalStreetVehicles
    // Write start and end links of chosen route into Activities.
    // We don't check yet whether the incoming and outgoing routes agree on the link an Activity is on.
    // Our aim should be that every transition from a link to another link be accounted for.
    val links = chosenTrip.legs.flatMap(l => l.beamLeg.travelPath.linkIds)
    if (links.nonEmpty) {
      _experiencedBeamPlan.activities(_currentActivityIndex).setLinkId(Id.createLinkId(links.head))
      _experiencedBeamPlan.activities(_currentActivityIndex + 1).setLinkId(Id.createLinkId(links.last))
    } else {
      val origin = beamServices.geo.utm2Wgs(_experiencedBeamPlan.activities(_currentActivityIndex).getCoord)
      val destination = beamServices.geo.utm2Wgs(_experiencedBeamPlan.activities(_currentActivityIndex + 1).getCoord)
      _experiencedBeamPlan.activities(_currentActivityIndex).setLinkId(Id.createLinkId(transportNetwork.streetLayer.findSplit(origin.getY, origin.getX, 1000.0, StreetMode.WALK).edge))
      _experiencedBeamPlan.activities(_currentActivityIndex + 1).setLinkId(Id.createLinkId(transportNetwork.streetLayer.findSplit(destination.getY, destination.getX, 1000.0, StreetMode.WALK).edge))
    }

    def availableAlternatives = {
      val theModes = choosesModeData.routingResponse.get.itineraries.map(_.tripClassifier).distinct
      if (choosesModeData.rideHailingResult.isDefined && choosesModeData.rideHailingResult.get.error.isEmpty) {
        theModes :+ RIDE_HAIL
      } else {
        theModes
      }
    }

    eventsManager.processEvent(new ModeChoiceEvent(tick, id, chosenTrip.tripClassifier.value, choosesModeData.expectedMaxUtilityOfLatestChoice.getOrElse[Double](Double.NaN),
      _experiencedBeamPlan.activities(_currentActivityIndex).getLinkId.toString, availableAlternatives.mkString(":"), choosesModeData.availablePersonalStreetVehicles.nonEmpty, chosenTrip.legs.map(_.beamLeg.travelPath.distanceInM).sum, _experiencedBeamPlan.tourIndexOfElement(nextActivity.right.get), chosenTrip))

    _experiencedBeamPlan.getStrategy(_experiencedBeamPlan.activities(_currentActivityIndex + 1), classOf[ModeChoiceStrategy]) match {
      case None =>
        _experiencedBeamPlan.putStrategy(currentTour, ModeChoiceStrategy(chosenTrip.tripClassifier))
      case _ =>
    }

    val personalVehicleUsed: Vector[Id[Vehicle]] = choosesModeData.availablePersonalStreetVehicles.map(_.id).intersect(chosenTrip.vehiclesInTrip)

    if (personalVehicleUsed.nonEmpty) {
      if (personalVehicleUsed.size > 1) {
        logWarn(s"Found multiple personal vehicle in use for chosenTrip: $chosenTrip but only expected one. Using " +
          s"only one for subsequent planning.")
      }
      currentTourPersonalVehicle = Some(personalVehicleUsed(0))
      availablePersonalStreetVehicles = availablePersonalStreetVehicles filterNot (_.id == personalVehicleUsed(0))
    }
    availablePersonalStreetVehicles.foreach { veh =>
      context.parent ! ReleaseVehicleReservation(id, veh.id)
      context.parent ! CheckInResource(veh.id, None)
    }
    if (chosenTrip.tripClassifier != RIDE_HAIL && choosesModeData.rideHailingResult.get.proposals.nonEmpty) {
      rideHailingManager ! ReleaseVehicleReservation(id, choosesModeData.rideHailingResult.get.proposals.head
        .rideHailingAgentLocation.vehicleId)
    }
    availablePersonalStreetVehicles = Vector()
    _currentTrip = Some(chosenTrip)
    _restOfCurrentTrip = chosenTrip
    scheduler ! completed(triggerId = theTriggerId, schedule[PersonDepartureTrigger](chosenTrip.legs.head.beamLeg.startTime, self))
  }

}

object ChoosesMode {
  case class ChoosesModeData(pendingChosenTrip: Option[EmbodiedBeamTrip] = None,
                             routingResponse: Option[RoutingResponse] = None,
                             rideHailingResult: Option[RideHailingInquiryResponse] = None,
                             availablePersonalStreetVehicles: Vector[StreetVehicle] = Vector(),
                             expectedMaxUtilityOfLatestChoice: Option[Double] = None) extends PersonData

  case class WaitingForReservationConfirmationData(pendingReservationConfirmation: Set[Id[ReservationRequest]], awaitingReservationConfirmation: Map[Id[ReservationRequest], (ActorRef, ReservationResponse)], choosesModeData: ChoosesModeData) extends PersonData

  case class LegWithPassengerVehicle(leg: EmbodiedBeamLeg, passengerVehicle: Id[Vehicle])

}

case class CancelReservation(reservationId: Id[ReservationRequest], passengerId: Id[Person])

case class CancelReservationWithVehicle(vehiclePersonId: VehiclePersonId)
