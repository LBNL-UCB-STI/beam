package beam.agentsim.agents.modalBehaviors

import java.time.{ZoneOffset, ZonedDateTime}

import akka.actor.FSM
import akka.actor.FSM.Failure
import beam.agentsim.Resource.CheckInResource
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents._
import beam.agentsim.agents.household.HouseholdActor.MobilityStatusInquiry.mobilityStatusInquiry
import beam.agentsim.agents.household.HouseholdActor.{MobilityStatusReponse, ReleaseVehicleReservation}
import beam.agentsim.agents.modalBehaviors.ChoosesMode.ChoosesModeData
import beam.agentsim.agents.rideHail.RideHailManager.{ReserveRide, RideHailInquiry, RideHailInquiryResponse}
import beam.agentsim.agents.rideHail.{RideHailAgent, RideHailManager}
import beam.agentsim.agents.vehicles.AccessErrorCodes.RideHailNotRequestedError
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{VehiclePersonId, _}
import beam.agentsim.events.{ModeChoiceEvent, SpaceTime}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.router.BeamRouter.{EmbodyWithCurrentTravelTime, RoutingRequest, RoutingResponse}
import beam.router.Modes
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode._
import beam.router.RoutingModel._
import beam.utils.{RoutingRequestResponseStats, RoutingRequestSenderCounter}
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
    case (PerformingActivity | Waiting | WaitingForTransitReservationConfirmation) -> ChoosingMode =>
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

      // Mark rideHailResult as None if we need to request a new one, or fake a result if we don't need to make a request
      val rideHailResult = choosesModeData.personData.currentTourMode match {
        case None | Some(RIDE_HAIL) =>
          None
        case _ =>
          Some(RideHailInquiryResponse(Id.create[RideHailInquiry]("NA", classOf[RideHailInquiry]), Vector(), Some(RideHailNotRequestedError)))
      }

      def makeRequestWith(transitModes: Vector[BeamMode], vehicles: Vector[StreetVehicle], streetVehiclesAsAccess: Boolean = true): Unit = {
        router ! RoutingRequest(currentActivity(choosesModeData.personData).getCoord, nextAct.getCoord,
          departTime, Modes.filterForTransit(transitModes), vehicles, streetVehiclesAsAccess, createdAt = ZonedDateTime.now(ZoneOffset.UTC))

        RoutingRequestSenderCounter.sent()
      }

      def makeRideHailRequest(): Unit = {
        rideHailManager ! RideHailInquiry(RideHailManager.nextRideHailInquiryId, id, currentActivity(choosesModeData.personData).getCoord, departTime, nextAct.getCoord)
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
                  router ! EmbodyWithCurrentTravelTime(leg, vehicle.id, createdAt = ZonedDateTime.now(ZoneOffset.UTC))
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
      stay() using choosesModeData.copy(availablePersonalStreetVehicles = availablePersonalStreetVehicles, rideHailResult = rideHailResult)
    /*
     * Receive and store data needed for choice.
     */
    case Event(theRouterResult: RoutingResponse, choosesModeData: ChoosesModeData) =>
      RoutingRequestResponseStats.add(theRouterResult.copy(receivedAt = Some(ZonedDateTime.now(ZoneOffset.UTC))))
      stay() using choosesModeData.copy(routingResponse = Some(theRouterResult))
    case Event(theRideHailResult: RideHailInquiryResponse, choosesModeData: ChoosesModeData) =>
      stay() using choosesModeData.copy(rideHailResult = Some(theRideHailResult))

  } using completeChoiceIfReady)

  when(WaitingForReservationConfirmation) (transform {
    case Event(ReservationResponse(_, Right(response)), choosesModeData: ChoosesModeData) =>
      val triggers = response.triggersToSchedule
      log.debug("scheduling triggers from reservation responses: {}", triggers)
      triggers.foreach(scheduler ! _)
      goto(FinishingModeChoice) using choosesModeData
    case Event(ReservationResponse(_, Left(firstErrorResponse)), choosesModeData: ChoosesModeData) =>
      if (choosesModeData.routingResponse.get.itineraries.isEmpty & choosesModeData.rideHailResult.get.error.isDefined) {
        // RideUnavailableError is defined for RHM and the trips are empty, but we don't check
        // if more agents could be hailed.
        stop(Failure(firstErrorResponse.errorCode.toString))
      } else {
        goto(ChoosingMode) using choosesModeData.copy(
          pendingChosenTrip = None,
          rideHailResult = Some(choosesModeData.rideHailResult.get.copy(proposals = Vector(), error = Some(firstErrorResponse))),
          routingResponse = choosesModeData.routingResponse
        )
      }
  } using completeChoiceIfReady)

  case object FinishingModeChoice extends BeamAgentState

  def completeChoiceIfReady: PartialFunction[State, State] = {
    case FSM.State(_, choosesModeData @ ChoosesModeData(personData, None, Some(routingResponse), Some(rideHailResult), _, _), _, _, _) =>
      val nextAct = nextActivity(choosesModeData.personData).right.get
      val combinedItinerariesForChoice = rideHailResult.proposals.flatMap(x => x.responseRideHail2Dest.itineraries) ++ routingResponse.itineraries
      val filteredItinerariesForChoice = personData.currentTourMode match {
        case Some(DRIVE_TRANSIT) =>
          val LastTripIndex = currentTour(choosesModeData.personData).trips.size - 1
          (currentTour(choosesModeData.personData).tripIndexOfElement(nextAct), personData.hasDeparted) match {
            case (0 | LastTripIndex, false) =>
              combinedItinerariesForChoice.filter(_.tripClassifier == DRIVE_TRANSIT)
            case _ =>
              combinedItinerariesForChoice.filter(_.tripClassifier == WALK_TRANSIT)
          }
        case Some(mode) =>
          combinedItinerariesForChoice.filter(_.tripClassifier == mode)
        case _ =>
          combinedItinerariesForChoice
      }
      modeChoiceCalculator(filteredItinerariesForChoice) match {
        case Some(chosenTrip) if RideHailAgent.getRideHailTrip(chosenTrip).nonEmpty =>
          val rideHailLeg = RideHailAgent.getRideHailTrip(chosenTrip)
          val departAt = DiscreteTime(rideHailLeg.head.beamLeg.startTime.toInt)
          rideHailManager ! ReserveRide(choosesModeData.rideHailResult.get.inquiryId, VehiclePersonId(bodyId, id), currentActivity(personData).getCoord, departAt, nextActivity(personData).right.get.getCoord)
          goto(WaitingForReservationConfirmation) using choosesModeData.copy(pendingChosenTrip = Some(chosenTrip))
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
        if (data.rideHailResult.isDefined && data.rideHailResult.get.error.isEmpty) {
          theModes :+ RIDE_HAIL
        } else {
          theModes
        }
      }

      eventsManager.processEvent(new ModeChoiceEvent(tick, id, chosenTrip.tripClassifier.value, data.expectedMaxUtilityOfLatestChoice.getOrElse[Double](Double.NaN),
        _experiencedBeamPlan.activities(data.personData.currentActivityIndex).getLinkId.toString, availableAlternatives.mkString(":"), data.availablePersonalStreetVehicles.nonEmpty, chosenTrip.legs.map(_.beamLeg.travelPath.distanceInM).sum, _experiencedBeamPlan.tourIndexOfElement(nextActivity(data.personData).right.get), chosenTrip))

      val personalVehicleUsed = data.availablePersonalStreetVehicles.map(_.id).intersect(chosenTrip.vehiclesInTrip).headOption

      val availablePersonalStreetVehicles = if (personalVehicleUsed.nonEmpty) {
        data.availablePersonalStreetVehicles.filterNot(_.id == personalVehicleUsed.get)
      }
      else data.availablePersonalStreetVehicles

      availablePersonalStreetVehicles.foreach { veh =>
        context.parent ! ReleaseVehicleReservation(id, veh.id)
        context.parent ! CheckInResource(veh.id, None)
      }
      if (chosenTrip.tripClassifier != RIDE_HAIL && data.rideHailResult.get.proposals.nonEmpty) {
        rideHailManager ! ReleaseVehicleReservation(id, data.rideHailResult.get.proposals.head
          .rideHailAgentLocation.vehicleId)
      }
      scheduler ! CompletionNotice(triggerId, Vector(ScheduleTrigger(PersonDepartureTrigger(math.max(chosenTrip.legs.head.beamLeg.startTime, tick)), self)))
      goto(WaitingForDeparture) using data.personData.copy(
        currentTrip = data.pendingChosenTrip,
        restOfCurrentTrip = data.pendingChosenTrip.get.legs.toList,
        currentTourMode = data.personData.currentTourMode.orElse(Some(chosenTrip.tripClassifier)),
        currentTourPersonalVehicle = data.personData.currentTourPersonalVehicle.orElse(personalVehicleUsed)
      )
  }
}

object ChoosesMode {
  case class ChoosesModeData(personData: BasePersonData, pendingChosenTrip: Option[EmbodiedBeamTrip] = None,
                             routingResponse: Option[RoutingResponse] = None,
                             rideHailResult: Option[RideHailInquiryResponse] = None,
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
