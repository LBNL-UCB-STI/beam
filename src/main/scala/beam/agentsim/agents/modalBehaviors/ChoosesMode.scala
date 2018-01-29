package beam.agentsim.agents.modalBehaviors

import akka.actor.ActorRef
import akka.actor.FSM.Failure
import beam.agentsim.Resource.CheckInResource
import beam.agentsim.agents.BeamAgent.{AnyState, BeamAgentInfo, Initialized, Uninitialized}
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.RideHailingManager.{ReserveRide, RideHailingInquiry, RideHailingInquiryResponse}
import beam.agentsim.agents.TriggerUtils._
import beam.agentsim.agents._
import beam.agentsim.agents.choice.logit.LatentClassChoiceModel.{Mandatory, TourType}
import beam.agentsim.agents.choice.mode.{ModeChoiceLCCM, ModeChoiceMultinomialLogit}
import beam.agentsim.agents.household.HouseholdActor.MobilityStatusInquiry._
import beam.agentsim.agents.household.HouseholdActor.{MobilityStatusReponse, ReleaseVehicleReservation}
import beam.agentsim.agents.modalBehaviors.ChoosesMode.{BeginModeChoiceTrigger, FinalizeModeChoiceTrigger, LegWithPassengerVehicle}
import beam.agentsim.agents.modalBehaviors.DrivesVehicle.NotifyLegStartTrigger
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator.AttributesOfIndividual
import beam.agentsim.agents.planning.Startegy.ModeChoiceStrategy
import beam.agentsim.agents.vehicles.AccessErrorCodes.RideHailNotRequestedError
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{VehiclePersonId, VehicleStack, _}
import beam.agentsim.events.{ModeChoiceEvent, SpaceTime}
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.agentsim.scheduler.{Trigger, TriggerWithId}
import beam.router.BeamRouter.{RoutingRequest, RoutingResponse}
import beam.router.Modes
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode._
import beam.router.RoutingModel._
import beam.sim.HasServices
import com.conveyal.r5.profile.StreetMode
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.vehicles.Vehicle

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.Random

/**
  * BEAM
  */
trait ChoosesMode extends BeamAgent[PersonData] with HasServices {
  this: PersonAgent => // Self type restricts this trait to only mix into a PersonAgent

  var routingResponse: Option[RoutingResponse] = None
  var rideHailingResult: Option[RideHailingInquiryResponse] = None
  var hasReceivedCompleteChoiceTrigger = false
  var awaitingReservationConfirmation: mutable.Map[Id[ReservationRequest], Option[ActorRef]] = mutable.Map()
  var pendingChosenTrip: Option[EmbodiedBeamTrip] = None
  var currentTourPersonalVehicle: Option[Id[Vehicle]] = None
  var availablePersonalStreetVehicles: Vector[StreetVehicle] = Vector()
  var expectedMaxUtilityOfLatestChoice: Option[Double] = None

  private def availableAlternatives = {
    val theModes = routingResponse.get.itineraries.map(_.tripClassifier).distinct
    if (rideHailingResult.isDefined && rideHailingResult.get.error.isEmpty) {
      theModes :+ RIDE_HAIL
    } else {
      theModes
    }
  }

  //TODO source these attributes from pop input data
  lazy val attributesOfIndividual: AttributesOfIndividual = AttributesOfIndividual(household.getIncome.getIncome,
    household.getMemberIds.size(),
    new Random().nextBoolean(),
    household.getVehicleIds.asScala.map(beamServices.vehicles).count(_.getType
      .getDescription.toLowerCase.contains("car")),
    household.getVehicleIds.asScala.map(beamServices.vehicles).count(_.getType
      .getDescription.toLowerCase.contains("bike")))

  def completeChoiceIfReady(): State = {
    if (hasReceivedCompleteChoiceTrigger && routingResponse.isDefined && rideHailingResult.isDefined) {
      val modeAlreadyDefined = _experiencedBeamPlan.getStrategy(nextActivity.right.get, classOf[ModeChoiceStrategy]).isDefined
      var predefinedMode: Option[BeamMode] = None
      var combinedItinerariesForChoice = rideHailingResult.get.proposals.flatMap(x => x.responseRideHailing2Dest.itineraries) ++
        routingResponse.get.itineraries


      if (modeAlreadyDefined) {
        predefinedMode = Some(_experiencedBeamPlan.getStrategy(nextActivity.right.get, classOf[ModeChoiceStrategy]).get.asInstanceOf[ModeChoiceStrategy].mode)
        if (predefinedMode.get != WALK) {
          val itinsWithoutWalk = if(predefinedMode.get == DRIVE_TRANSIT){
            combinedItinerariesForChoice.filter(itin => itin.tripClassifier == CAR || itin.tripClassifier == DRIVE_TRANSIT)
          }else{
            combinedItinerariesForChoice.filter(_.tripClassifier != WALK)
          }
          if (itinsWithoutWalk.nonEmpty) combinedItinerariesForChoice = itinsWithoutWalk
        }
      }
      if (combinedItinerariesForChoice.isEmpty) {
        assert(combinedItinerariesForChoice.nonEmpty, "Empty choice set.")
      }

      var chosenTrip: EmbodiedBeamTrip = modeChoiceCalculator match {
        case logit: ModeChoiceLCCM =>
          val tourType: TourType = Mandatory
          logit(combinedItinerariesForChoice, Some(attributesOfIndividual), tourType)
        case logit: ModeChoiceMultinomialLogit =>
          val trip = logit(combinedItinerariesForChoice)
          expectedMaxUtilityOfLatestChoice = Some(logit.expectedMaximumUtility)
          trip
        case _ =>
          modeChoiceCalculator(combinedItinerariesForChoice)
      }

      if (chosenTrip.requiresReservationConfirmation) {
        pendingChosenTrip = Some(chosenTrip)
        sendReservationRequests(chosenTrip)
      } else {
        scheduleDepartureWithValidatedTrip(chosenTrip)
      }
    } else {
      stay()
    }
  }

  def sendReservationRequests(chosenTrip: EmbodiedBeamTrip): State = {

    var inferredVehicle: VehicleStack = VehicleStack()
    var exitNextVehicle = false
    var legsWithPassengerVehicle: Vector[LegWithPassengerVehicle] = Vector()
    val rideHailingLeg = RideHailingAgent.getRideHailingTrip(chosenTrip)

    if (rideHailingLeg.nonEmpty) {
      val departAt = DiscreteTime(rideHailingLeg.head.beamLeg.startTime.toInt)
      val rideHailingId = Id.create(rideHailingResult.get.inquiryId.toString, classOf[ReservationRequest])
      rideHailingManager ! ReserveRide(rideHailingResult.get.inquiryId, VehiclePersonId
      (_humanBodyVehicle, id), currentActivity.getCoord, departAt, nextActivity.right.get.getCoord)
      awaitingReservationConfirmation = awaitingReservationConfirmation + (rideHailingId -> None)
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
          awaitingReservationConfirmation = awaitingReservationConfirmation + (resRequest.request.requestId -> None)
        }
      }
    }
    stay()
  }


  def scheduleDepartureWithValidatedTrip(chosenTrip: EmbodiedBeamTrip, triggersToSchedule: Vector[ScheduleTrigger] =
  Vector()): State = {

    val (tick, theTriggerId) = releaseTickAndTriggerId()

    // Write start and end links of chosen route into Activities.
    // We don't check yet whether the incoming and outgoing routes agree on the link an Activity is on.
    // Our aim should be that every transition from a link to another link be accounted for.
    val links = chosenTrip.legs.flatMap(l => l.beamLeg.travelPath.linkIds)
    if (links.nonEmpty) {
      _experiencedBeamPlan.activities(_currentActivityIndex).setLinkId(Id.createLinkId(links.head))
      _experiencedBeamPlan.activities(_currentActivityIndex+1).setLinkId(Id.createLinkId(links.last))
    } else {
      val origin = beamServices.geo.utm2Wgs(_experiencedBeamPlan.activities(_currentActivityIndex).getCoord)
      val destination = beamServices.geo.utm2Wgs(_experiencedBeamPlan.activities(_currentActivityIndex+1).getCoord)
      _experiencedBeamPlan.activities(_currentActivityIndex).setLinkId(Id.createLinkId(transportNetwork.streetLayer.findSplit(origin.getY, origin.getX, 1000.0, StreetMode.WALK).edge))
      _experiencedBeamPlan.activities(_currentActivityIndex+1).setLinkId(Id.createLinkId(transportNetwork.streetLayer.findSplit(destination.getY, destination.getX, 1000.0, StreetMode.WALK).edge))
    }

    eventsManager.processEvent(new ModeChoiceEvent(tick, id, chosenTrip.tripClassifier.value, expectedMaxUtilityOfLatestChoice.getOrElse[Double](Double.NaN),
      _experiencedBeamPlan.activities(_currentActivityIndex).getLinkId.toString, availableAlternatives.mkString(":"), availablePersonalStreetVehicles.nonEmpty, chosenTrip.legs.map(_.beamLeg.travelPath.distanceInM).sum, _experiencedBeamPlan.tourIndexOfElement(nextActivity.right.get)))

    _experiencedBeamPlan.getStrategy(_experiencedBeamPlan.activities(_currentActivityIndex+1), classOf[ModeChoiceStrategy]) match {
      case None =>
        _experiencedBeamPlan.putStrategy(currentTour, ModeChoiceStrategy(chosenTrip.tripClassifier))
      case _ =>
    }

    val personalVehicleUsed: Vector[Id[Vehicle]] = availablePersonalStreetVehicles.map(_.id).intersect(chosenTrip.vehiclesInTrip)

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
    if (chosenTrip.tripClassifier != RIDE_HAIL && rideHailingResult.get.proposals.nonEmpty) {
      rideHailingManager ! ReleaseVehicleReservation(id, rideHailingResult.get.proposals.head
        .rideHailingAgentLocation.vehicleId)
    }
    availablePersonalStreetVehicles = Vector()
    _currentTrip = Some(chosenTrip)
    _restOfCurrentTrip = chosenTrip
    routingResponse = None
    rideHailingResult = None
    if(awaitingReservationConfirmation.nonEmpty){
      awaitingReservationConfirmation.clear()
    }
    hasReceivedCompleteChoiceTrigger = false
    pendingChosenTrip = None
    scheduler ! completed(triggerId = theTriggerId, triggersToSchedule ++
      schedule[PersonDepartureTrigger](chosenTrip.legs.head.beamLeg.startTime, self))
    goto(Waiting)
  }

  chainedWhen(Uninitialized) {
    case Event(TriggerWithId(InitializeTrigger(_), _), _) =>
      goto(Initialized)
  }

  chainedWhen(ChoosingMode) {
    /*
     * Begin Choice Process
     *
     * When we begin the mode choice process, we send out requests for data that we need from other system components.
     * Then we reply with a completion notice and schedule the finalize choice trigger.
     */
    case Event(TriggerWithId(BeginModeChoiceTrigger(tick), triggerId), _: BeamAgentInfo[PersonData]) =>
      holdTickAndTriggerId(tick, triggerId)
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
      stay()
    case Event(MobilityStatusReponse(streetVehicles), _: BeamAgentInfo[PersonData]) =>
      val (tick, theTriggerId) = releaseTickAndTriggerId()
      val bodyStreetVehicle = StreetVehicle(_humanBodyVehicle, SpaceTime(currentActivity.getCoord, tick.toLong),
        WALK, asDriver = true)
      availablePersonalStreetVehicles = streetVehicles.filter(_.asDriver)

      val nextAct = nextActivity.right.get
      val departTime = DiscreteTime(tick.toInt)

      val modeChoiceStrategy = _experiencedBeamPlan.getStrategy(nextAct, classOf[ModeChoiceStrategy]).asInstanceOf[Option[ModeChoiceStrategy]]
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
          makeRequestWith(Vector(), filterStreetVehiclesForQuery(streetVehicles, mode) :+ bodyStreetVehicle)
        case Some(ModeChoiceStrategy(mode)) if mode == DRIVE_TRANSIT =>
          currentTour.tripIndexOfElement(nextAct) match {
            case ind if ind==0 =>
              makeRequestWith(Vector(TRANSIT), filterStreetVehiclesForQuery(streetVehicles, CAR) :+ bodyStreetVehicle)
            case ind if ind == currentTour.trips.size-1 =>
              makeRequestWith(Vector(TRANSIT), filterStreetVehiclesForQuery(streetVehicles, CAR) :+ bodyStreetVehicle, streetVehiclesAsAccess = false)
            case _ =>
              makeRequestWith(Vector(TRANSIT), Vector(bodyStreetVehicle))
          }
        case Some(ModeChoiceStrategy(mode)) if mode == RIDE_HAIL =>
          makeRequestWith(Vector(), Vector(bodyStreetVehicle)) // We need a WALK alternative if RH fails
          makeRideHailRequest()
      }

      scheduler ! completed(theTriggerId, schedule[FinalizeModeChoiceTrigger](tick, self))
      stay()
    /*
     * Receive and store data needed for choice.
     */
    case Event(theRouterResult: RoutingResponse, _: BeamAgentInfo[PersonData]) =>
      routingResponse = Some(theRouterResult)
      completeChoiceIfReady()
    case Event(theRideHailingResult: RideHailingInquiryResponse, _: BeamAgentInfo[PersonData]) =>
      rideHailingResult = Some(theRideHailingResult)
      completeChoiceIfReady()
    /*
     * Process ReservationReponses
     */
    case Event(ReservationResponse(requestId, Right(reservationConfirmation)), _) =>
      awaitingReservationConfirmation = awaitingReservationConfirmation + (requestId -> Some(sender()))
      if (awaitingReservationConfirmation.values.forall(x => x.isDefined)) {
        scheduleDepartureWithValidatedTrip(pendingChosenTrip.get, reservationConfirmation.triggersToSchedule)
      } else {
        stay()
      }
    case Event(ReservationResponse(requestId, Left(error)), _) =>

      pendingChosenTrip.get.tripClassifier match {
        case RIDE_HAIL =>
          awaitingReservationConfirmation = awaitingReservationConfirmation - requestId
          rideHailingResult = Some(rideHailingResult.get.copy(proposals = Vector(), error = Some(error)))
        case _ =>
          routingResponse = Some(routingResponse.get.copy(itineraries = routingResponse.get.itineraries.diff(Seq
          (pendingChosenTrip.get))))
      }
      cancelReservations()
      if (routingResponse.get.itineraries.isEmpty & rideHailingResult.get.error.isDefined) {
        // RideUnavailableError is defined for RHM and the trips are empty, but we don't check
        // if more agents could be hailed.
        stop(Failure(error.errorCode.toString))
      } else {
        pendingChosenTrip = None
        completeChoiceIfReady()
      }
    case Event(ReservationResponse(_, _), _) =>
      stop(Failure("unknown res response"))
    /*
     * Finishing choice.
     */
    case Event(TriggerWithId(FinalizeModeChoiceTrigger(tick), theTriggerId), _: BeamAgentInfo[PersonData]) =>
      holdTickAndTriggerId(tick, theTriggerId)
      hasReceivedCompleteChoiceTrigger = true
      completeChoiceIfReady()
    case Event(TriggerWithId(NotifyLegStartTrigger(tick, beamLeg),theTriggerId),_) =>
      // We've received this leg too early...
      stash()
      stay()
  }

  onTransition {
    case ChoosingMode -> Waiting =>
      unstashAll()
  }

  chainedWhen(AnyState) {
    case Event(res@ReservationResponse(_, _), _) =>
      stay()
  }

  def cancelReservations(): Unit = {
    cancelTrip(pendingChosenTrip.get.legs, _currentVehicle)
    awaitingReservationConfirmation.clear()
  }


}

object ChoosesMode {

  case class BeginModeChoiceTrigger(tick: Double) extends Trigger

  case class FinalizeModeChoiceTrigger(tick: Double) extends Trigger

  case class LegWithPassengerVehicle(leg: EmbodiedBeamLeg, passengerVehicle: Id[Vehicle])

}

case class CancelReservation(reservationId: Id[ReservationRequest], passengerId: Id[Person])

case class CancelReservationWithVehicle(vehiclePersonId: VehiclePersonId)