package beam.agentsim.agents.modalBehaviors

import java.util.Collections

import beam.agentsim.agents.BeamAgent.BeamAgentInfo
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.RideHailingManager.{ReserveRide, RideHailingInquiry, RideHailingInquiryResponse, RideUnavailableError}
import beam.agentsim.agents.modalBehaviors.ChoosesMode.{BeginModeChoiceTrigger, FinalizeModeChoiceTrigger, LegWithPassengerVehicle}
import beam.agentsim.agents.vehicles.BeamVehicle.StreetVehicle
import beam.agentsim.agents.vehicles.household.HouseholdActor.MobilityStatusReponse
import beam.agentsim.agents._
import beam.utils.CollectionUtils._
import beam.agentsim.agents.TriggerUtils._
import beam.agentsim.agents.vehicles.household.HouseholdActor.MobilityStatusInquiry._
import beam.agentsim.agents.vehicles.{VehiclePersonId, VehicleStack}
import beam.agentsim.events.AgentsimEventsBus.MatsimEvent
import beam.agentsim.events.resources.ReservationError
import beam.agentsim.events.{ModeChoiceEvent, SpaceTime}
import beam.agentsim.events.resources.vehicle.{Reservation, ReservationRequest, ReservationRequestWithVehicle, ReservationResponse}
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.agentsim.scheduler.{Trigger, TriggerWithId}
import beam.router.BeamRouter.{RoutingRequest, RoutingResponse}
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode._
import beam.router.RoutingModel._
import beam.sim.HasServices
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.PersonDepartureEvent
import org.matsim.vehicles.Vehicle

import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.util.Random

/**
  * BEAM
  */
trait ChoosesMode extends BeamAgent[PersonData] with HasServices {
  this: PersonAgent => // Self type restricts this trait to only mix into a PersonAgent

  var routingResponse: Option[RoutingResponse] = None
  var rideHailingResult: Option[RideHailingInquiryResponse] = None
  var hasReceivedCompleteChoiceTrigger = false
  var awaitingReservationConfirmation: Set[Id[ReservationRequest]] = Set()
  var pendingChosenTrip: Option[EmbodiedBeamTrip] = None
  var currentTourPersonalVehicle: Option[Id[Vehicle]] = None
  var availablePersonalStreetVehicles: Vector[Id[Vehicle]] = Vector()


  def completeChoiceIfReady(): State = {
    if (hasReceivedCompleteChoiceTrigger && routingResponse.isDefined && rideHailingResult.isDefined) {

      val combinedItinerariesForChoice: Vector[EmbodiedBeamTrip] = if(rideHailingResult.get.proposals.nonEmpty){
        rideHailingResult.get.proposals.flatMap(x=>x.responseRideHailing2Dest.itineraries)++routingResponse.get.itineraries
      }
      else{
        routingResponse.get.itineraries
      }

      val chosenTrip = beamServices.modeChoiceCalculator(combinedItinerariesForChoice)

      chosenTrip match {
        case Some(theChosenTrip) if theChosenTrip.legs.nonEmpty =>
          if(tripRequiresReservationConfirmation(theChosenTrip)){
            pendingChosenTrip = chosenTrip
            sendReservationRequests(theChosenTrip)
          }else{
            scheduleDepartureWithValidatedTrip(theChosenTrip)
          }
        case _ =>
          errorFromEmptyRoutingResponse()
      }
    } else {
      stay()
    }
  }

  def sendReservationRequests(chosenTrip: EmbodiedBeamTrip) = {
    //TODO this is currently working for single leg Transit trips, hasn't been tested on multi-leg transit trips (e.g. BUS WALK BUS)
    if(id.toString.equals("2276-3")){
      val i = 0
    }

    var inferredVehicle: VehicleStack = VehicleStack()
    var exitNextVehicle = false
    var legsWithPassengerVehicle: Vector[LegWithPassengerVehicle] = Vector()
    val rideHailingLeg = RideHailingAgent.getRideHailingTrip(chosenTrip)
    // XXXX: Sorry... this is so ugly
    if(rideHailingLeg.nonEmpty){
      val departAt=DiscreteTime(rideHailingLeg.head.beamLeg.startTime.toInt)
      val rideHailingVehicleId = rideHailingResult.get.proposals.head.rideHailingAgentLocation.vehicleId
      val rideHailingId = Id.create(rideHailingResult.get.inquiryId.toString,classOf[ReservationRequest])
      beamServices.rideHailingManager ! ReserveRide(rideHailingResult.get.inquiryId,VehiclePersonId(_humanBodyVehicle,id),currentActivity.getCoord,departAt,nextActivity.right.get.getCoord)
      awaitingReservationConfirmation = awaitingReservationConfirmation + rideHailingId
    }else {
      for (leg <- chosenTrip.legs) {
        if (exitNextVehicle) inferredVehicle = inferredVehicle.pop()

        if (inferredVehicle.nestedVehicles.nonEmpty) {
          legsWithPassengerVehicle = legsWithPassengerVehicle :+ LegWithPassengerVehicle(leg,inferredVehicle.outermostVehicle())
        }
        inferredVehicle = inferredVehicle.pushIfNew(leg.beamVehicleId)
        exitNextVehicle = (leg.asDriver && leg.unbecomeDriverOnCompletion) || !leg.asDriver
      }
      val ungroupedLegs = legsWithPassengerVehicle.filter(_.leg.beamLeg.mode.isTransit).toList
      var runningVehId = ungroupedLegs.head.leg.beamVehicleId
      var groupedLegs = List[List[LegWithPassengerVehicle]]()
      var currentSegmentList = List[LegWithPassengerVehicle]()
      ungroupedLegs.foreach{ legwithpass =>
        if(legwithpass.leg.beamVehicleId == runningVehId) {
          currentSegmentList = currentSegmentList :+ legwithpass
        }else{
          groupedLegs = groupedLegs :+ currentSegmentList
          currentSegmentList = List(legwithpass)
          runningVehId = legwithpass.leg.beamVehicleId
        }
        groupedLegs = groupedLegs.slice(0,groupedLegs.size-1) :+ currentSegmentList
      }
      if (groupedLegs.nonEmpty) {
        groupedLegs.foreach { legSegment =>
          val legs = legSegment.sortBy(_.leg.beamLeg.startTime)
          val vehId = legSegment.head.leg.beamVehicleId
          val driverRef = beamServices.agentRefs(beamServices.transitDriversByVehicle(vehId).toString)
          val resRequest = ReservationRequestWithVehicle(new ReservationRequest(legs.head.leg.beamLeg, legs.last.leg.beamLeg, VehiclePersonId(legs.head.passengerVehicle,id)), vehId)
          driverRef ! resRequest
          awaitingReservationConfirmation = awaitingReservationConfirmation + resRequest.request.requestId
        }
      }
    }
    stay()
  }


  def scheduleDepartureWithValidatedTrip(chosenTrip: EmbodiedBeamTrip, triggersToSchedule: Vector[ScheduleTrigger] = Vector()) = {
    if(id.toString.equals("2276-3")){
      val i = 0
    }
    val (tick, theTriggerId) = releaseTickAndTriggerId()
    beamServices.agentSimEventsBus.publish(MatsimEvent(new ModeChoiceEvent(tick, id, chosenTrip.tripClassifier.value)))
    beamServices.agentSimEventsBus.publish(MatsimEvent(new PersonDepartureEvent(tick, id, currentActivity.getLinkId, chosenTrip.tripClassifier.matsimMode)))
    val personalVehicleUsed = availablePersonalStreetVehicles.intersect(chosenTrip.vehiclesInTrip)
    if(!personalVehicleUsed.isEmpty){
      if(personalVehicleUsed.size>1){
        logWarn(s"Found multiple personal vehicle in use for chosenTrip: ${chosenTrip} but only expected one. Using only one for subequent planning.")
      }
      currentTourPersonalVehicle = Some(personalVehicleUsed(0))
    }
    _currentRoute = chosenTrip
    availablePersonalStreetVehicles = Vector()
    routingResponse = None
    rideHailingResult = None
    hasReceivedCompleteChoiceTrigger = false
    pendingChosenTrip = None
    beamServices.schedulerRef ! completed(triggerId = theTriggerId, triggersToSchedule ++ schedule[PersonDepartureTrigger](chosenTrip.legs.head.beamLeg.startTime, self))
    goto(Waiting)
  }
  /*
   * If any leg of a trip is not conducted as the drive, than a reservation must be acquired
   */
  def tripRequiresReservationConfirmation(chosenTrip: EmbodiedBeamTrip): Boolean = chosenTrip.legs.exists(!_.asDriver)

  def errorFromEmptyRoutingResponse(): ChoosesMode.this.State = {
    log.error(s"No trip chosen because RoutingResponse empty, person $id going to Error")
    beamServices.schedulerRef ! completed(triggerId = _currentTriggerId.get)
    goto(BeamAgent.Error)
  }

  chainedWhen(ChoosingMode) {
    /*
     * Begin Choice Process
     *
     * When we begin the mode choice process, we send out requests for data that we need from other system components.
     * Then we reply with a completion notice and schedule the finalize choice trigger.
     */
    case Event(TriggerWithId(BeginModeChoiceTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      logInfo(s"inside ChoosesMode @ $tick")
      holdTickAndTriggerId(tick,triggerId)
      beamServices.householdRefs.get(_household).foreach(_  ! mobilityStatusInquiry(id))
      stay()
    case Event(MobilityStatusReponse(streetVehicles), info: BeamAgentInfo[PersonData]) =>
      val (tick,theTriggerId) = releaseTickAndTriggerId()
      val bodyStreetVehicle = StreetVehicle(_humanBodyVehicle,SpaceTime(currentActivity.getCoord,tick.toLong),WALK,true)
      availablePersonalStreetVehicles = streetVehicles.filter(_.asDriver).map(_.id)

      val nextAct = nextActivity.right.get
      val departTime = DiscreteTime(tick.toInt)
      //val departTime = BeamTime.within(stateData.data.currentActivity.getEndTime.toInt)

      currentTourPersonalVehicle match {
        case Some(personalVeh) if availablePersonalStreetVehicles.contains(personalVeh) =>
          beamServices.beamRouter ! RoutingRequest(currentActivity, nextAct, departTime, Vector(), streetVehicles.filter(_.id == personalVeh) :+ bodyStreetVehicle, id)
        case Some(personalVeh) =>
          logWarn(s"Person appears to be in a tour using vehicle ${personalVeh} but MobilityStatusResponse did not contain this vehilce, so considering all modes.")
          beamServices.beamRouter ! RoutingRequest(currentActivity, nextAct, departTime, Vector(BeamMode.TRANSIT), streetVehicles :+ bodyStreetVehicle, id)
        case None =>
          beamServices.beamRouter ! RoutingRequest(currentActivity, nextAct, departTime, Vector(BeamMode.TRANSIT), streetVehicles :+ bodyStreetVehicle, id)
      }

      //TODO parameterize search distance
      val pickUpLocation = currentActivity.getCoord
      beamServices.rideHailingManager ! RideHailingInquiry(RideHailingManager.nextRideHailingInquiryId, id, pickUpLocation, departTime, nextAct.getCoord)

      beamServices.schedulerRef ! completed(theTriggerId, schedule[FinalizeModeChoiceTrigger](tick, self))
      stay()
    /*
     * Receive and store data needed for choice.
     */
    case Event(theRouterResult: RoutingResponse, info: BeamAgentInfo[PersonData]) =>
      routingResponse = Some(theRouterResult)
      completeChoiceIfReady()
    case Event(theRideHailingResult: RideHailingInquiryResponse, info: BeamAgentInfo[PersonData]) =>
      rideHailingResult = Some(theRideHailingResult)
      completeChoiceIfReady()
    /*
     * Process ReservationReponses
     */
    case Event(ReservationResponse(requestId,Right(reservationConfirmation)),_) =>
      awaitingReservationConfirmation = awaitingReservationConfirmation - requestId
      if(awaitingReservationConfirmation.isEmpty){
        scheduleDepartureWithValidatedTrip(pendingChosenTrip.get, reservationConfirmation.triggersToSchedule)
      }else{
        stay()
      }
    case Event(ReservationResponse(requestId,Left(_)),_) =>
      pendingChosenTrip.get.tripClassifier match {
        case RIDEHAIL =>
          rideHailingResult = Some(rideHailingResult.get.copy(proposals = Vector(),error = Some(RideUnavailableError)))
        case _ =>
          routingResponse = Some(routingResponse.get.copy(itineraries=routingResponse.get.itineraries.diff(Seq(pendingChosenTrip))))
      }
      if(routingResponse.get.itineraries.isEmpty & rideHailingResult.get.error.isDefined){
        errorFromEmptyRoutingResponse()
      }else{
        completeChoiceIfReady()
      }
    case Event(ReservationResponse(_,_),_)=>
      log.error("unknown res response")
      errorFromEmptyRoutingResponse()

    /*
     * Finishing choice.
     */
    case Event(TriggerWithId(FinalizeModeChoiceTrigger(tick), theTriggerId), info: BeamAgentInfo[PersonData]) =>
      holdTickAndTriggerId(tick,theTriggerId)
      hasReceivedCompleteChoiceTrigger = true
      completeChoiceIfReady()
  }

}
object ChoosesMode {

  case class BeginModeChoiceTrigger(tick: Double) extends Trigger
  case class FinalizeModeChoiceTrigger(tick: Double) extends Trigger

  case class LegWithPassengerVehicle(leg: EmbodiedBeamLeg, passengerVehicle: Id[Vehicle])
}
