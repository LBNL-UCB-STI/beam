package beam.agentsim.agents.modalBehaviors

import akka.actor.ActorRef
import akka.actor.FSM.Failure
import beam.agentsim.Resource.ResourceIsAvailableNotification
import beam.agentsim.agents.BeamAgent.{AnyState, BeamAgentInfo, Initialized, Uninitialized}
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.RideHailingManager.{ReserveRide, RideHailingInquiry, RideHailingInquiryResponse}
import beam.agentsim.agents.TriggerUtils._
import beam.agentsim.agents._
import beam.agentsim.agents.choice.logit.LatentClassChoiceModel.{Mandatory, TourType}
import beam.agentsim.agents.choice.mode.{ModeChoiceLCCM, ModeChoiceMultinomialLogit}
import beam.agentsim.agents.modalBehaviors.ChoosesMode.{BeginModeChoiceTrigger, FinalizeModeChoiceTrigger, LegWithPassengerVehicle}
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator.AttributesOfIndividual
import beam.agentsim.agents.planning.Startegy.ModeChoiceStrategy
import beam.agentsim.agents.vehicles.BeamVehicle.StreetVehicle
import beam.agentsim.agents.vehicles.household.HouseholdActor.MobilityStatusInquiry._
import beam.agentsim.agents.vehicles.household.HouseholdActor.{MobilityStatusReponse, ReleaseVehicleReservation}
import beam.agentsim.agents.vehicles.{VehiclePersonId, VehicleStack}
import beam.agentsim.events.resources.vehicle.{ReservationRequest, ReservationRequestWithVehicle, ReservationResponse}
import beam.agentsim.events.{ModeChoiceEvent, SpaceTime}
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.agentsim.scheduler.{Trigger, TriggerWithId}
import beam.router.BeamRouter.{RoutingRequest, RoutingResponse}
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode._
import beam.router.RoutingModel._
import beam.sim.HasServices
import beam.agentsim.events.resources.vehicle.RideHailNotRequested
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.PersonDepartureEvent
import org.matsim.api.core.v01.population.Person
import org.matsim.vehicles.Vehicle

import scala.collection.mutable
import scala.collection.JavaConverters._
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
  var modeChoiceCalculator: ModeChoiceCalculator = _
  var expectedMaxUtilityOfLatestChoice: Option[Double] = None

  private def availableAlternatives = {
    val theModes = routingResponse.get.itineraries.map(_.tripClassifier).distinct
    if(rideHailingResult.isDefined && rideHailingResult.get.error.isEmpty){
      theModes :+ RIDEHAIL
    }else{
      theModes
    }
  }

  //TODO source these attributes from pop input data
  lazy val attributesOfIndividual: AttributesOfIndividual = AttributesOfIndividual(beamServices.households(_household).getIncome.getIncome,
    beamServices.households(_household).getMemberIds.size(),
    new Random().nextBoolean(),
    beamServices.households(_household).getVehicleIds.asScala.map(beamServices.vehicles(_)).count(_.getType.getDescription.toLowerCase.contains("car")),
    beamServices.households(_household).getVehicleIds.asScala.map(beamServices.vehicles(_)).count(_.getType.getDescription.toLowerCase.contains("bike")))

  def completeChoiceIfReady(): State = {
    if (hasReceivedCompleteChoiceTrigger && routingResponse.isDefined && rideHailingResult.isDefined) {
      val combinedItinerariesForChoice =
        rideHailingResult.get.proposals.flatMap(x => x.responseRideHailing2Dest.itineraries) ++
        routingResponse.get.itineraries
      assert(combinedItinerariesForChoice.nonEmpty, "Empty choice set.")

      val chosenTrip: EmbodiedBeamTrip = modeChoiceCalculator match {
        case logit: ModeChoiceLCCM =>
          val tourType : TourType = Mandatory
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
      beamServices.rideHailingManager ! ReserveRide(rideHailingResult.get.inquiryId, VehiclePersonId(_humanBodyVehicle, id), currentActivity.getCoord, departAt, nextActivity.right.get.getCoord)
      awaitingReservationConfirmation = awaitingReservationConfirmation + (rideHailingId -> None)
    } else {
      var prevLeg = chosenTrip.legs.head
      for (leg <- chosenTrip.legs) {
        if (exitNextVehicle || (!prevLeg.asDriver && leg.beamVehicleId != prevLeg.beamVehicleId)) inferredVehicle = inferredVehicle.pop()
//        if (exitNextVehicle) inferredVehicle = inferredVehicle.pop()

        if (inferredVehicle.nestedVehicles.nonEmpty) {
          val passengerVeh: Id[Vehicle] = if(inferredVehicle.outermostVehicle() == leg.beamVehicleId){
            if(inferredVehicle.nestedVehicles.size<2){
              // In this case, we are changing into a WALK leg
              Id.create("dummy",classOf[Vehicle])
            }else{
              inferredVehicle.penultimateVehicle() }
          }else{ inferredVehicle.outermostVehicle() }
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
          val resRequest = ReservationRequestWithVehicle(new ReservationRequest(legs.head.leg.beamLeg, legs.last.leg.beamLeg, VehiclePersonId(legs.head.passengerVehicle, id)), vehId)
          TransitDriverAgent.selectByVehicleId(vehId) ! resRequest
          awaitingReservationConfirmation = awaitingReservationConfirmation + (resRequest.request.requestId -> None)
        }
      }
    }
    stay()
  }


  def scheduleDepartureWithValidatedTrip(chosenTrip: EmbodiedBeamTrip, triggersToSchedule: Vector[ScheduleTrigger] = Vector()): State = {

    val (tick, theTriggerId) = releaseTickAndTriggerId()

    val location = if(chosenTrip.legs.nonEmpty && chosenTrip.legs.head.beamLeg.travelPath.linkIds.nonEmpty){ chosenTrip.legs.head.beamLeg.travelPath.linkIds.head.toString }else{ "" }

    context.system.eventStream.publish(new ModeChoiceEvent(tick, id, chosenTrip.tripClassifier.value, expectedMaxUtilityOfLatestChoice.getOrElse[Double](Double.NaN),
      location,availableAlternatives.mkString(":"),availablePersonalStreetVehicles.nonEmpty,chosenTrip.legs.map(_.beamLeg.travelPath.distanceInM).sum))

    val personalVehicleUsed: Vector[Id[Vehicle]] = availablePersonalStreetVehicles.map(_.id).intersect(chosenTrip.vehiclesInTrip)

    if (personalVehicleUsed.nonEmpty) {
      if (personalVehicleUsed.size > 1) {
        logWarn(s"Found multiple personal vehicle in use for chosenTrip: $chosenTrip but only expected one. Using only one for subequent planning.")
      }
      currentTourPersonalVehicle = Some(personalVehicleUsed(0))
      availablePersonalStreetVehicles = availablePersonalStreetVehicles filterNot(_.id == personalVehicleUsed(0))
    }
    val householdRef: ActorRef = beamServices.householdRefs(_household)
    availablePersonalStreetVehicles.foreach { veh =>
      householdRef ! ReleaseVehicleReservation(id, veh.id)
      householdRef ! ResourceIsAvailableNotification(self, veh.id, new SpaceTime(currentActivity.getCoord, tick.toLong))
    }
    if (chosenTrip.tripClassifier != RIDEHAIL && rideHailingResult.get.proposals.nonEmpty) {
      beamServices.rideHailingManager ! ReleaseVehicleReservation(id, rideHailingResult.get.proposals.head.rideHailingAgentLocation.vehicleId)
    }
    availablePersonalStreetVehicles = Vector()
    _currentRoute = chosenTrip
    routingResponse = None
    rideHailingResult = None
    awaitingReservationConfirmation.clear()
    hasReceivedCompleteChoiceTrigger = false
    pendingChosenTrip = None
    beamServices.schedulerRef ! completed(triggerId = theTriggerId, triggersToSchedule ++ schedule[PersonDepartureTrigger](chosenTrip.legs.head.beamLeg.startTime, self))
    goto(Waiting)
  }

  chainedWhen(Uninitialized){
    case Event(TriggerWithId(InitializeTrigger(_), _), _) =>
      modeChoiceCalculator = beamServices.modeChoiceCalculator.clone()
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
      logInfo(s"inside ChoosesMode @ $tick")
      holdTickAndTriggerId(tick, triggerId)
      val modeChoiceStrategy = _experiencedBeamPlan.getStrategy(nextActivity.right.get,classOf[ModeChoiceStrategy]).asInstanceOf[Option[ModeChoiceStrategy]]
      modeChoiceStrategy match {
        case None | Some(mode) if mode == CAR || mode == BIKE || mode == DRIVE_TRANSIT =>
          // Only need to get available street vehicles from household if our mode requires such a vehicle
          beamServices.householdRefs.get(_household).foreach(_ ! mobilityStatusInquiry(id))
        case _ =>
          // Otherwise, send empty list to self
          self ! MobilityStatusReponse(Vector())
      }
      stay()
    case Event(MobilityStatusReponse(streetVehicles), _: BeamAgentInfo[PersonData]) =>
      val (tick, theTriggerId) = releaseTickAndTriggerId()
      val bodyStreetVehicle = StreetVehicle(_humanBodyVehicle, SpaceTime(currentActivity.getCoord, tick.toLong), WALK, asDriver = true)
      val nextAct = nextActivity.right.get
      val departTime = DiscreteTime(tick.toInt)

      val modeChoiceStrategy = _experiencedBeamPlan.getStrategy(nextAct,classOf[ModeChoiceStrategy]).asInstanceOf[Option[ModeChoiceStrategy]]
      modeChoiceStrategy match {
        case None | Some(mode) if mode == CAR || mode == BIKE || mode == DRIVE_TRANSIT =>
        // In these cases, a personal vehicle will be involved
          availablePersonalStreetVehicles = streetVehicles.filter(_.asDriver)
        case _ =>
          availablePersonalStreetVehicles = Vector()
      }

      // Mark rideHailingResult
      modeChoiceStrategy match {
        case None | Some(mode) if mode == RIDEHAIL =>
          rideHailingResult = None
        case _ =>
          rideHailingResult = Some(RideHailingInquiryResponse(Id.create[RideHailingInquiry]("NA", classOf[RideHailingInquiry]), Vector(), Some(RideHailNotRequested)))
      }

      // Form and send requests
      modeChoiceStrategy match {
        case None | Some(mode) if mode == WALK_TRANSIT =>
          val req = RoutingRequest(currentActivity, nextAct, departTime, Vector(TRANSIT), Vector(bodyStreetVehicle), id)
          beamServices.beamRouter ! req
          logInfo(req.toString)
          beamServices.rideHailingManager ! RideHailingInquiry(RideHailingManager.nextRideHailingInquiryId, id, currentActivity.getCoord, departTime, nextAct.getCoord)
        case Some(mode) if mode == CAR || mode == BIKE =>
          val streetVehiclesToUseInQuery = currentTourPersonalVehicle match {
            case Some(personalVeh) =>
              // We already have a vehicle we're using on this tour, so filter down to that
              streetVehicles.filter(_.id == personalVeh) :+ bodyStreetVehicle
            case None =>
              // Just filter down to any CAR or BIKE
              streetVehicles.filter(_.mode == mode) :+ bodyStreetVehicle
          }
          val req = RoutingRequest(currentActivity, nextAct, departTime, Vector(), streetVehiclesToUseInQuery, id)
          beamServices.beamRouter ! req
          logInfo(req.toString)
        case Some(mode) if mode == DRIVE_TRANSIT =>
          val streetVehiclesToUseInQuery = currentTourPersonalVehicle match {
            case Some(personalVeh) =>
              // We already have a vehicle we're using on this tour, so filter down to that
              streetVehicles.filter(_.id == personalVeh) :+ bodyStreetVehicle
            case None =>
              streetVehicles.filter(_.mode == CAR) :+ bodyStreetVehicle
          }
          val req = RoutingRequest(currentActivity, nextAct, departTime, Vector(), streetVehiclesToUseInQuery, id, streetVehiclesAsAccess = false)
          beamServices.beamRouter ! req
          logInfo(req.toString)
        case Some(mode) if mode == RIDEHAIL =>
          beamServices.rideHailingManager ! RideHailingInquiry(RideHailingManager.nextRideHailingInquiryId, id, currentActivity.getCoord, departTime, nextAct.getCoord)
      }

      beamServices.schedulerRef ! completed(theTriggerId, schedule[FinalizeModeChoiceTrigger](tick, self))
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
        case RIDEHAIL =>
          awaitingReservationConfirmation = awaitingReservationConfirmation - requestId
          rideHailingResult = Some(rideHailingResult.get.copy(proposals = Vector(), error = Some(error)))
        case _ =>
          routingResponse = Some(routingResponse.get.copy(itineraries = routingResponse.get.itineraries.diff(Seq(pendingChosenTrip.get))))
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
  }
  chainedWhen(AnyState) {
    case Event(res@ReservationResponse(_, _), _) =>
      logWarn(s"Reservation confirmation received from state $stateName: ${res.response}")
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
