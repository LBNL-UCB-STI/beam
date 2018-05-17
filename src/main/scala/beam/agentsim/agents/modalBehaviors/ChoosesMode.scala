package beam.agentsim.agents.modalBehaviors

import akka.actor.FSM
import akka.actor.FSM.Failure
import beam.agentsim.Resource.CheckInResource
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents._
import beam.agentsim.agents.household.HouseholdActor.MobilityStatusInquiry.mobilityStatusInquiry
import beam.agentsim.agents.household.HouseholdActor.{MobilityStatusReponse, ReleaseVehicleReservation}
import beam.agentsim.agents.modalBehaviors.ChoosesMode.ChoosesModeData
import beam.agentsim.agents.rideHail.RideHailingManager.{ReserveRide, RideHailingInquiry, RideHailingInquiryResponse}
import beam.agentsim.agents.rideHail.{RideHailingAgent, RideHailingManager}
import beam.agentsim.agents.vehicles.AccessErrorCodes.RideHailNotRequestedError
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{VehiclePersonId, _}
import beam.agentsim.events.{ModeChoiceEvent, SpaceTime}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.TriggerWithId
import beam.router.BeamRouter.{EmbodyWithCurrentTravelTime, RoutingRequest, RoutingResponse}
import beam.router.Modes
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode._
import beam.router.RoutingModel._
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Leg
import org.matsim.core.population.routes.NetworkRoute
import org.matsim.vehicles.Vehicle

import scala.collection.JavaConverters._
import scala.concurrent.duration._


/**
  * BEAM
  */
trait ChoosesMode {
  this: PersonAgent => // Self type restricts this trait to only mix into a PersonAgent

  onTransition {
    case (PerformingActivity | Waiting | WaitingForReservationConfirmation) -> ChoosingMode =>
      stateData.asInstanceOf[BasePersonData].currentTourMode match {
        case Some(CAR | BIKE | DRIVE_TRANSIT)  =>
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
    case Event(MobilityStatusReponse(streetVehicles), choosesModeData: ChoosesModeData) =>
      val bodyStreetVehicle = StreetVehicle(bodyId, SpaceTime(currentActivity(choosesModeData.personData).getCoord, _currentTick.get.toLong), WALK, asDriver = true)
      val nextAct = nextActivity(choosesModeData.personData).right.get
      val departTime = DiscreteTime(_currentTick.get.toInt)
      val availablePersonalStreetVehicles = choosesModeData.personData.currentTourMode match {
        case None | Some(CAR | BIKE) =>
          // In these cases, a personal vehicle will be involved
          streetVehicles.filter(_.asDriver)
        case Some(DRIVE_TRANSIT)=>
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

      var rideHailingResult = choosesModeData.rideHailingResult
      var rideHail2TransitAccessResult = choosesModeData.rideHail2TransitAccessResult
      var rideHail2TransitEgressResult = choosesModeData.rideHail2TransitEgressResult
      // Mark rideHailingResult as None if we need to request a new one, or fake a result if we don't need to make a request
      choosesModeData.personData.currentTourMode match {
        case Some(RIDE_HAIL) =>
          rideHailingResult = None
        case Some(RIDE_HAIL_TRANSIT) =>
          rideHail2TransitAccessResult = None
          rideHail2TransitEgressResult = None
        case None =>
          rideHailingResult = None
          rideHail2TransitAccessResult = None
          rideHail2TransitEgressResult = None
        case _ =>
          rideHailingResult = Some(dummyRideHailResponse())
          rideHail2TransitAccessResult = Some(dummyRideHailResponse())
          rideHail2TransitEgressResult = Some(dummyRideHailResponse())
      }

      def makeRequestWith(transitModes: Vector[BeamMode], vehicles: Vector[StreetVehicle], streetVehiclesAsAccess: Boolean = true): Unit = {
        router ! RoutingRequest(currentActivity(choosesModeData.personData).getCoord, nextAct.getCoord, departTime, Modes.filterForTransit(transitModes), vehicles, streetVehiclesAsAccess)
      }

      def makeRideHailRequest(): Unit = {
        rideHailingManager ! RideHailingInquiry(RideHailingManager.nextRideHailingInquiryId, id, currentActivity(choosesModeData.personData).getCoord, departTime, nextAct.getCoord)
      }

      def filterStreetVehiclesForQuery(streetVehicles: Vector[StreetVehicle], byMode: BeamMode): Vector[StreetVehicle] = {
        choosesModeData.personData.currentTourPersonalVehicle match {
          case Some(personalVeh) =>
            // We already have a vehicle we're using on this tour, so filter down to that
            streetVehicles.filter(_.id == personalVeh)
          case None =>
            // Otherwise, filter by mode
            streetVehicles.filter(_.mode == byMode)
        }
      }

      // Form and send requests
      choosesModeData.personData.currentTourMode match {
        case None =>
          makeRequestWith(Vector(TRANSIT), streetVehicles :+ bodyStreetVehicle)
          makeRideHailRequest()
        case Some(WALK) =>
          makeRequestWith(Vector(), Vector(bodyStreetVehicle))
        case Some(WALK_TRANSIT) =>
          makeRequestWith(Vector(TRANSIT), Vector(bodyStreetVehicle))
        case Some(mode @ (CAR | BIKE)) =>
          val maybeLeg = _experiencedBeamPlan.getPlanElements.get(_experiencedBeamPlan.getPlanElements.indexOf(nextAct)-1) match {
            case l: Leg => Some(l)
            case _ => None
          }
          maybeLeg.map(l => (l, l.getRoute)) match {
            case Some((l, r: NetworkRoute)) =>
              val maybeVehicle = filterStreetVehiclesForQuery(streetVehicles, mode).headOption
              maybeVehicle match {
                case Some(vehicle) =>
                  val leg = BeamLeg(departTime.atTime, mode, l.getTravelTime.toLong, BeamPath((r.getStartLinkId +: r.getLinkIds.asScala :+ r.getEndLinkId).map(id => id.toString.toInt).toVector, None, SpaceTime.zero, SpaceTime.zero, r.getDistance))
                  router ! EmbodyWithCurrentTravelTime(leg, vehicle.id)
                case _ =>
                  makeRequestWith(Vector(), Vector(bodyStreetVehicle))
              }
            case _ =>
              makeRequestWith(Vector(), filterStreetVehiclesForQuery(streetVehicles, mode) :+ bodyStreetVehicle)
          }
        case Some(DRIVE_TRANSIT) =>
          val LastTripIndex = currentTour(choosesModeData.personData).trips.size - 1
          (currentTour(choosesModeData.personData).tripIndexOfElement(nextAct), choosesModeData.personData.currentTourPersonalVehicle) match {
            case (0,_) =>
              makeRequestWith(Vector(TRANSIT), filterStreetVehiclesForQuery(streetVehicles, CAR) :+ bodyStreetVehicle)
            // At the end of the tour, only drive home a vehicle that we have also taken away from there.
            case (LastTripIndex, Some(currentTourPersonalVehicleId)) =>
              makeRequestWith(Vector(TRANSIT), streetVehicles.filter(_.id == currentTourPersonalVehicleId) :+ bodyStreetVehicle, streetVehiclesAsAccess = false)
            case _ =>
              makeRequestWith(Vector(TRANSIT), Vector(bodyStreetVehicle))
          }
        case Some(RIDE_HAIL) =>
          makeRequestWith(Vector(), Vector(bodyStreetVehicle)) // We need a WALK alternative if RH fails
          makeRideHailRequest()
        case Some(m) => logDebug(s"$m: other then expected")
      }
      val newPersonData = choosesModeData.copy(availablePersonalStreetVehicles = availablePersonalStreetVehicles, rideHailingResult = rideHailingResult,
        rideHail2TransitAccessResult = rideHail2TransitAccessResult, rideHail2TransitEgressResult = rideHail2TransitEgressResult)
      stay() using newPersonData
    /*
     * Receive and store data needed for choice.
     */
    case Event(theRouterResult: RoutingResponse, choosesModeData: ChoosesModeData) =>
      var accessId: Option[Id[RideHailingInquiry]] = None
      var egressId: Option[Id[RideHailingInquiry]] = None
      // If there's a walk-transit trip AND we don't have an error RH2Tr response (due to no desire to use RH) then seek RH on access and egress
      val walkTransitTrip = theRouterResult.itineraries.dropWhile(_.tripClassifier != WALK_TRANSIT).headOption
      val newPersonData = if(shouldAttemptRideHail2Transit(walkTransitTrip,choosesModeData.rideHail2TransitAccessResult)){
        val accessSegment = walkTransitTrip.get.legs.takeWhile(!_.beamLeg.mode.isMassTransit()).map(_.beamLeg)
        val egressSegment = walkTransitTrip.get.legs.dropWhile(!_.beamLeg.mode.isMassTransit()).dropWhile(_.beamLeg.mode.isMassTransit()).map(_.beamLeg)
        //TODO replace hard code number here with parameter
        accessId = if(accessSegment.map(_.travelPath.distanceInM).sum > 0){makeRideHailRequestFromBeamLeg(accessSegment)}else{None}
        egressId = if(egressSegment.map(_.travelPath.distanceInM).sum > 0){makeRideHailRequestFromBeamLeg(egressSegment)}else{None}
        choosesModeData.copy(routingResponse = Some(theRouterResult), rideHail2TransitAccessInquiryId = accessId, rideHail2TransitEgressInquiryId = egressId)
      }else{
        choosesModeData.copy(routingResponse = Some(theRouterResult), rideHail2TransitAccessResult = Some(dummyRideHailResponse()), rideHail2TransitEgressResult = Some(dummyRideHailResponse()))
      }
      stay() using newPersonData
    case Event(theRideHailingResult: RideHailingInquiryResponse, choosesModeData: ChoosesModeData) =>
      val newPersonData = Some(theRideHailingResult.inquiryId) match {
        case choosesModeData.rideHail2TransitAccessInquiryId =>
          choosesModeData.copy(rideHail2TransitAccessResult = Some(theRideHailingResult))
        case choosesModeData.rideHail2TransitEgressInquiryId =>
          choosesModeData.copy(rideHail2TransitEgressResult = Some(theRideHailingResult))
        case _ =>
          choosesModeData.copy(rideHailingResult = Some(theRideHailingResult))
      }
      stay() using newPersonData

  } using completeChoiceIfReady)

  def shouldAttemptRideHail2Transit(walkTransitTrip: Option[EmbodiedBeamTrip], rideHail2TransitResult: Option[RideHailingInquiryResponse]): Boolean = {
    walkTransitTrip.isDefined && walkTransitTrip.get.legs.dropWhile(_.beamLeg.mode.isMassTransit()).size > 0 &&
      rideHail2TransitResult.getOrElse(dummyRideHailResponse(false)).error.isEmpty
  }
  def makeRideHailRequestFromBeamLeg(legs: Vector[BeamLeg]): Option[Id[RideHailingInquiry]] = {
    val inquiryId = RideHailingManager.nextRideHailingInquiryId
    rideHailingManager ! RideHailingInquiry(inquiryId, id, beamServices.geo.wgs2Utm(legs.head.travelPath.startPoint.loc), DiscreteTime(legs.head.startTime.toInt), beamServices.geo.wgs2Utm(legs.last.travelPath.endPoint.loc))
    Some(inquiryId)
  }

  case object FinishingModeChoice extends BeamAgentState

  def createRideHail2TransitItin(rideHail2TransitAccessResult: RideHailingInquiryResponse, rideHail2TransitEgressResult: RideHailingInquiryResponse, routingResponse: RoutingResponse): Option[EmbodiedBeamTrip] = {
    if(rideHail2TransitAccessResult.error.isEmpty){
      val walkTransitTrip = routingResponse.itineraries.dropWhile(_.tripClassifier != WALK_TRANSIT).head
      val tncAccessLeg = rideHail2TransitAccessResult.proposals.head.responseRideHailing2Dest.itineraries.head.legs.dropRight(1)
      // Replacing walk access leg with TNC changes the travel time.
      val differenceInAccessDuration = walkTransitTrip.legs.head.beamLeg.duration - tncAccessLeg.last.beamLeg.duration
      if(differenceInAccessDuration < 0){
        // Travel time increases in rare cases due to sloppiness in occasionally using the link as a proxy for the point location
        None
      }else{
        // Travel time usually decreases, adjust for this but add a buffer to the wait time to account for uncertainty in actual wait time
        val startTimeBufferForWaiting = math.max(60.0,rideHail2TransitAccessResult.proposals.head.timesToCustomer * 1.5) // tncAccessLeg.head.beamLeg.startTime - _currentTick.get.longValue()
        val accessAndTransit = tncAccessLeg.map(leg => leg.copy(leg.beamLeg.updateStartTime(leg.beamLeg.startTime + differenceInAccessDuration - startTimeBufferForWaiting.longValue()))) ++ walkTransitTrip.legs.tail
        val fullTrip = if(rideHail2TransitEgressResult.error.isEmpty){
          accessAndTransit.dropRight(1) ++ rideHail2TransitEgressResult.proposals.head.responseRideHailing2Dest.itineraries.head.legs.tail
        }else{
          accessAndTransit
        }
        Some(EmbodiedBeamTrip(fullTrip))
      }
    }else{
      None
    }
  }

  def completeChoiceIfReady: PartialFunction[State, State] = {
    case FSM.State(_, choosesModeData @ ChoosesModeData(personData, None, Some(routingResponse), Some(rideHailingResult), Some(rideHail2TransitAccessResult), _,Some(rideHail2TransitEgressResult),_,_,_), _, _, _) =>
      val nextAct = nextActivity(choosesModeData.personData).right.get
      val rideHail2TransitIinerary = createRideHail2TransitItin(rideHail2TransitAccessResult, rideHail2TransitEgressResult, routingResponse)
      val combinedItinerariesForChoice = rideHailingResult.proposals.flatMap(x => x.responseRideHailing2Dest.itineraries) ++
        routingResponse.itineraries ++ rideHail2TransitIinerary.toVector
    //      val test = createRideHail2TransitItin(rideHail2TransitAccessResult, rideHail2TransitEgressResult, routingResponse)
      val filteredItinerariesForChoice = personData.currentTourMode match {
        case Some(DRIVE_TRANSIT) =>
          val LastTripIndex = currentTour(choosesModeData.personData).trips.size - 1
          (currentTour(choosesModeData.personData).tripIndexOfElement(nextAct), personData.hasDeparted) match {
            case (0 | LastTripIndex, false) =>
              combinedItinerariesForChoice.filter(_.tripClassifier == DRIVE_TRANSIT)
            case _ =>
              combinedItinerariesForChoice.filter(trip => trip.tripClassifier == WALK_TRANSIT || trip.tripClassifier == RIDE_HAIL_TRANSIT)
          }
        case Some(mode) if mode == WALK_TRANSIT || mode == RIDE_HAIL_TRANSIT =>
          combinedItinerariesForChoice.filter(trip => trip.tripClassifier == WALK_TRANSIT || trip.tripClassifier == RIDE_HAIL_TRANSIT)
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
          val originalWalkTripLeg = routingResponse.itineraries.filter(_.tripClassifier == WALK).head.legs.head
          val expensiveWalkTrip = EmbodiedBeamTrip(Vector(originalWalkTripLeg.copy(cost = BigDecimal(100.0))))
          goto(FinishingModeChoice) using choosesModeData.copy(pendingChosenTrip = Some(expensiveWalkTrip))
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
        _experiencedBeamPlan.activities(data.personData.currentActivityIndex).setLinkId(Id.createLinkId(links.head))
        _experiencedBeamPlan.activities(data.personData.currentActivityIndex + 1).setLinkId(Id.createLinkId(links.last))
      } else {
        val origin = beamServices.geo.utm2Wgs(_experiencedBeamPlan.activities(data.personData.currentActivityIndex).getCoord)
        val destination = beamServices.geo.utm2Wgs(_experiencedBeamPlan.activities(data.personData.currentActivityIndex + 1).getCoord)
        _experiencedBeamPlan.activities(data.personData.currentActivityIndex).setLinkId(Id.createLinkId(beamServices.geo.getNearestR5Edge(transportNetwork.streetLayer,origin,10000)))
        _experiencedBeamPlan.activities(data.personData.currentActivityIndex + 1).setLinkId(Id.createLinkId(beamServices.geo.getNearestR5Edge(transportNetwork.streetLayer,destination,10000)))
      }

      def availableAlternatives = {
        val theModes = data.routingResponse.get.itineraries.map(_.tripClassifier).distinct
        if (data.rideHailingResult.isDefined && data.rideHailingResult.get.error.isEmpty) {
          theModes :+ RIDE_HAIL
        } else {
          theModes
        }
      }

      eventsManager.processEvent(new ModeChoiceEvent(tick, id, chosenTrip.tripClassifier.value, data.expectedMaxUtilityOfLatestChoice.getOrElse[Double](Double.NaN),
        _experiencedBeamPlan.activities(data.personData.currentActivityIndex).getLinkId.toString, availableAlternatives.mkString(":"), data.availablePersonalStreetVehicles.nonEmpty, chosenTrip.legs.map(_.beamLeg.travelPath.distanceInM).sum, _experiencedBeamPlan.tourIndexOfElement(nextActivity(data.personData).right.get), chosenTrip))

      val personalVehicleUsed = data.availablePersonalStreetVehicles.map(_.id).intersect(chosenTrip.vehiclesInTrip).headOption

      var availablePersonalStreetVehicles = data.availablePersonalStreetVehicles
      if (personalVehicleUsed.nonEmpty) {
        availablePersonalStreetVehicles = availablePersonalStreetVehicles filterNot (_.id == personalVehicleUsed.get)
      }
      availablePersonalStreetVehicles.foreach { veh =>
        context.parent ! ReleaseVehicleReservation(id, veh.id)
        context.parent ! CheckInResource(veh.id, None)
      }
      if (chosenTrip.tripClassifier != RIDE_HAIL && data.rideHailingResult.get.proposals.nonEmpty) {
        rideHailingManager ! ReleaseVehicleReservation(id, data.rideHailingResult.get.proposals.head
          .rideHailingAgentLocation.vehicleId)
      }
      scheduler ! CompletionNotice(triggerId, Vector(ScheduleTrigger(PersonDepartureTrigger(math.max(chosenTrip.legs.head.beamLeg.startTime, tick)), self)))
      goto(WaitingForDeparture) using data.personData.copy(
        currentTrip = Some(chosenTrip),
        restOfCurrentTrip = chosenTrip.legs.toList,
        currentTourMode = data.personData.currentTourMode.orElse(Some(chosenTrip.tripClassifier)),
        currentTourPersonalVehicle = data.personData.currentTourPersonalVehicle.orElse(personalVehicleUsed)
      )
  }

  def dummyRideHailResponse(withError: Boolean = true) = RideHailingInquiryResponse(Id.create[RideHailingInquiry]("NA", classOf[RideHailingInquiry]), Vector(), if(withError){ Some(RideHailNotRequestedError) }else{ None })
}

object ChoosesMode {
  case class ChoosesModeData(personData: BasePersonData, pendingChosenTrip: Option[EmbodiedBeamTrip] = None,
                             routingResponse: Option[RoutingResponse] = None,
                             rideHailingResult: Option[RideHailingInquiryResponse] = None,
                             rideHail2TransitAccessResult: Option[RideHailingInquiryResponse] = None,
                             rideHail2TransitAccessInquiryId: Option[Id[RideHailingInquiry]] = None,
                             rideHail2TransitEgressResult: Option[RideHailingInquiryResponse] = None,
                             rideHail2TransitEgressInquiryId: Option[Id[RideHailingInquiry]] = None,
                             availablePersonalStreetVehicles: Vector[StreetVehicle] = Vector(),
                             expectedMaxUtilityOfLatestChoice: Option[Double] = None) extends PersonData {
    override def currentVehicle: VehicleStack = personData.currentVehicle
    override def currentLegPassengerScheduleIndex: Int = personData.currentLegPassengerScheduleIndex
    override def passengerSchedule: PassengerSchedule = personData.passengerSchedule
    override def withPassengerSchedule(newPassengerSchedule: PassengerSchedule): DrivingData = copy(personData = personData.copy(passengerSchedule = newPassengerSchedule))
    override def withCurrentLegPassengerScheduleIndex(currentLegPassengerScheduleIndex: Int): DrivingData = copy(personData = personData.copy(currentLegPassengerScheduleIndex = currentLegPassengerScheduleIndex))
  }

  case class LegWithPassengerVehicle(leg: EmbodiedBeamLeg, passengerVehicle: Id[Vehicle])

}
