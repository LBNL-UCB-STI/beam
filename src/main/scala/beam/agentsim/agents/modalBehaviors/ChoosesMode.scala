package beam.agentsim.agents.modalBehaviors

import beam.agentsim.agents.BeamAgent.BeamAgentInfo
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.RideHailingManager.{ReserveRide, RideHailingInquiry, RideHailingInquiryResponse}
import beam.agentsim.agents.modalBehaviors.ChoosesMode.{BeginModeChoiceTrigger, ChoiceCalculator, FinalizeModeChoiceTrigger, LegWithPassengerVehicle}
import beam.agentsim.agents.vehicles.BeamVehicle.StreetVehicle
import beam.agentsim.agents.vehicles.household.HouseholdActor.{MobilityStatusInquiry, MobilityStatusReponse}
import beam.agentsim.agents._
import beam.agentsim.agents.TriggerUtils._
import beam.agentsim.agents.vehicles.{VehiclePersonId, VehicleStack}
import beam.agentsim.events.AgentsimEventsBus.MatsimEvent
import beam.agentsim.events.SpaceTime
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

import scala.util.Random

/**
  * BEAM
  */
trait ChoosesMode extends BeamAgent[PersonData] with HasServices {
  this: PersonAgent => // Self type restricts this trait to only mix into a PersonAgent

  val choiceCalculator: ChoiceCalculator = ChoosesMode.transitIfAvailable
  var routingResponse: Option[RoutingResponse] = None
  var rideHailingResult: Option[RideHailingInquiryResponse] = None
  var hasReceivedCompleteChoiceTrigger = false
  var awaitingReservationConfirmation: Set[Id[ReservationRequest]] = Set()
  var pendingChosenTrip: Option[EmbodiedBeamTrip] = None


  def completeChoiceIfReady(): State = {
    if (hasReceivedCompleteChoiceTrigger && routingResponse.isDefined && rideHailingResult.isDefined) {

      val combinedItinerariesForChoice: Vector[EmbodiedBeamTrip] = if(rideHailingResult.get.proposals.nonEmpty){
        rideHailingResult.get.proposals.flatMap(x=>x.responseRideHailing2Dest.itineraries)++routingResponse.get.itineraries
      }
      else{
        routingResponse.get.itineraries
      }

      val chosenTrip = choiceCalculator(combinedItinerariesForChoice)

      if(tripRequiresReservationConfirmation(chosenTrip)){
        pendingChosenTrip = Some(chosenTrip)
        sendReservationRequests(chosenTrip)
      }else if(chosenTrip.legs.isEmpty) {
        errorFromEmptyRoutingResponse()
      }else{
        scheduleDepartureWithValidatedTrip(chosenTrip)
      }
    } else {
      stay()
    }
  }

  def sendReservationRequests(chosenTrip: EmbodiedBeamTrip) = {
    //TODO this is currently working for single leg Transit trips, hasn't been tested on multi-leg transit trips (e.g. BUS WALK BUS)

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
      val transitLegs = legsWithPassengerVehicle.filter(_.leg.beamLeg.mode.isTransit)
      if (transitLegs.nonEmpty) {
        transitLegs.toVector.groupBy(_.leg.beamVehicleId).foreach { idToLegs =>
          val legs = idToLegs._2.sortBy(_.leg.beamLeg.startTime)
          val driverRef = beamServices.agentRefs(beamServices.transitDriversByVehicle(idToLegs._1).toString)
          val resRequest = ReservationRequestWithVehicle(new ReservationRequest(legs.head.leg.beamLeg, legs.last.leg.beamLeg, VehiclePersonId(legs.head.passengerVehicle,id)), idToLegs._1)
          driverRef ! resRequest
          awaitingReservationConfirmation = awaitingReservationConfirmation + resRequest.request.requestId
        }
      }
    }
    stay()
  }



  def scheduleDepartureWithValidatedTrip(chosenTrip: EmbodiedBeamTrip, triggersToSchedule: Vector[ScheduleTrigger] = Vector()) = {
    val (tick, theTriggerId) = releaseTickAndTriggerId()
    beamServices.agentSimEventsBus.publish(MatsimEvent(new PersonDepartureEvent(tick, id, currentActivity.getLinkId, chosenTrip.tripClassifier.matsimMode)))
    _currentRoute = chosenTrip
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
  def tripRequiresReservationConfirmation(chosenTrip: EmbodiedBeamTrip): Boolean = {
    chosenTrip.legs.filter(!_.asDriver).size > 0
  }
  def errorFromEmptyRoutingResponse(): ChoosesMode.this.State = {
    log.error(s"No trip chosen because RoutingResponse empty, person ${id} going to Error")
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
      logInfo(s"inside ChoosesMode @ ${tick}")
      holdTickAndTriggerId(tick,triggerId)
      beamServices.householdRefs.get(_household).foreach(_  ! MobilityStatusInquiry(id))
      stay()
    case Event(MobilityStatusReponse(streetVehicles), info: BeamAgentInfo[PersonData]) =>
      val (tick,theTriggerId) = releaseTickAndTriggerId()
      val bodyStreetVehicle = StreetVehicle(_humanBodyVehicle,SpaceTime(currentActivity.getCoord,tick.toLong),WALK,true)

      val nextAct = nextActivity.right.get // No danger of failure here
      val departTime = DiscreteTime(tick.toInt)
      //val departTime = BeamTime.within(stateData.data.currentActivity.getEndTime.toInt)

//      beamServices.beamRouter ! RoutingRequest(currentActivity, nextAct, departTime, Vector(BeamMode.CAR, BeamMode.BIKE, BeamMode.WALK, BeamMode.TRANSIT), id)
      beamServices.beamRouter ! RoutingRequest(currentActivity, nextAct, departTime, Vector(BeamMode.TRANSIT), streetVehicles :+ bodyStreetVehicle, id)
//      beamServices.beamRouter ! RoutingRequest(currentActivity, nextAct, departTime, Vector(), streetVehicles :+ bodyStreetVehicle, id)

      //TODO parameterize search distance
      val pickUpLocation = currentActivity.getCoord
      beamServices.rideHailingManager ! RideHailingInquiry(RideHailingManager.nextRideHailingInquiryId, id, pickUpLocation, departTime, 50000, nextAct.getCoord)

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
    case Event(ReservationResponse(requestId,Right(resrvationConfirmation)),_) =>
      awaitingReservationConfirmation = awaitingReservationConfirmation - requestId
      if(awaitingReservationConfirmation.isEmpty){
        scheduleDepartureWithValidatedTrip(pendingChosenTrip.get, resrvationConfirmation.triggersToSchedule)
      }else{
        stay()
      }
    case Event(ReservationResponse(requestId,Left(resrvationError)),_) =>
      routingResponse = Some(routingResponse.get.copy(itineraries=routingResponse.get.itineraries.diff(Seq(pendingChosenTrip))))
      if(routingResponse.get.itineraries.isEmpty){
        errorFromEmptyRoutingResponse()
      }else{
        completeChoiceIfReady()
      }

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
  type ChoiceCalculator = (Vector[EmbodiedBeamTrip]) => EmbodiedBeamTrip

  case class BeginModeChoiceTrigger(tick: Double) extends Trigger
  case class FinalizeModeChoiceTrigger(tick: Double) extends Trigger

  def transitIfAvailable(alternatives: Vector[EmbodiedBeamTrip]): EmbodiedBeamTrip = {
    var containsTransitAlt: Vector[Int] = Vector[Int]()
    alternatives.zipWithIndex.foreach{ alt =>
      if(alt._1.tripClassifier.isTransit){
        containsTransitAlt = containsTransitAlt :+ alt._2
      }
    }
    val chosenIndex = if (containsTransitAlt.size > 0){ containsTransitAlt.head }else{ 0 }
    if(alternatives.size > 0) {
      alternatives(chosenIndex)
    } else {
      EmbodiedBeamTrip.empty
    }
  }

  def rideHailIfAvailable(alternatives: Vector[EmbodiedBeamTrip]): EmbodiedBeamTrip = {
    var containsDriveAlt: Vector[Int] = Vector[Int]()
    alternatives.zipWithIndex.foreach{ alt =>
      if(alt._1.tripClassifier == RIDEHAIL){
        containsDriveAlt = containsDriveAlt :+ alt._2
      }
    }
    val chosenIndex = if (containsDriveAlt.size > 0){ containsDriveAlt.head }else{ 0 }
    if(alternatives.size > 0) {
      alternatives(chosenIndex)
    } else {
      EmbodiedBeamTrip.empty
    }
  }
  def driveIfAvailable(alternatives: Vector[EmbodiedBeamTrip]): EmbodiedBeamTrip = {
    var containsDriveAlt: Vector[Int] = Vector[Int]()
    alternatives.zipWithIndex.foreach{ alt =>
      if(alt._1.tripClassifier == CAR){
        containsDriveAlt = containsDriveAlt :+ alt._2
      }
    }
    val chosenIndex = if (containsDriveAlt.size > 0){ containsDriveAlt.head }else{ 0 }
    if(alternatives.size > 0) {
      alternatives(chosenIndex)
    } else {
      EmbodiedBeamTrip.empty
    }
  }

  def mnlChoice(alternatives: Vector[EmbodiedBeamTrip]): EmbodiedBeamTrip = {
    var containsDriveAlt = -1

    var altModesAndTimes: Vector[(BeamMode, Double)] = for (i <- alternatives.indices.toVector) yield {
      val alt = alternatives(i)
      val altMode = if (alt.legs.size == 1) {
        alt.legs.head.beamLeg.mode
      } else {
        if (alt.legs.head.beamLeg.mode.equals(CAR)) {
          containsDriveAlt = i
          CAR
        } else {
          TRANSIT
        }
      }
      val travelTime = (for (leg <- alt.legs) yield leg.beamLeg.duration).foldLeft(0.0) {
        _ + _
      }
      (altMode, travelTime)
    }

    val altUtilities = for (alt <- altModesAndTimes) yield altUtility(alt._1, alt._2)
    val sumExpUtilities = altUtilities.foldLeft(0.0)(_ + math.exp(_))
    val altProbabilities = for (util <- altUtilities) yield math.exp(util) / sumExpUtilities
    val cumulativeAltProbabilities = altProbabilities.scanLeft(0.0)(_ + _)
    //TODO replace with RNG in services
    val randDraw = Random.nextDouble()
    val chosenIndex = for (i <- 1 until cumulativeAltProbabilities.length if randDraw < cumulativeAltProbabilities(i)) yield i - 1
    if(chosenIndex.size > 0) {
      alternatives(chosenIndex.head)
    } else {
      EmbodiedBeamTrip.empty
    }
  }

  def altUtility(mode: BeamMode, travelTime: Double): Double = {
    val intercept = if(mode.equals(CAR)){ -3.0 }else{ if(mode.equals(RIDEHAIL)){ -5.0}else{0.0} }
    intercept + -0.001 * travelTime
  }

  def randomChoice(alternatives: Vector[BeamTrip]): BeamTrip = {
    Random.shuffle(alternatives.toList).head
  }
  case class LegWithPassengerVehicle(leg: EmbodiedBeamLeg, passengerVehicle: Id[Vehicle])
}
