package beam.agentsim.agents.modalBehaviors

import akka.actor.FSM.Failure
import akka.actor.{ActorRef, FSM}
import beam.agentsim.Resource.CheckInResource
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.RideHailingManager.{ReserveRide, RideHailingInquiry, RideHailingInquiryResponse}
import beam.agentsim.agents.TriggerUtils._
import beam.agentsim.agents._
import beam.agentsim.agents.household.HouseholdActor.MobilityStatusInquiry.mobilityStatusInquiry
import beam.agentsim.agents.household.HouseholdActor.{MobilityStatusReponse, ReleaseVehicleReservation}
import beam.agentsim.agents.modalBehaviors.ChoosesMode.{ChoosesModeData, LegWithPassengerVehicle, WaitingForReservationConfirmationData}
import beam.agentsim.agents.planning.Strategy.ModeChoiceStrategy
import beam.agentsim.agents.vehicles.AccessErrorCodes.RideHailNotRequestedError
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{VehiclePersonId, VehicleStack, _}
import beam.agentsim.events.{ModeChoiceEvent, SpaceTime}
import beam.router.BeamRouter.{EmbodyWithCurrentTravelTime, RoutingRequest, RoutingResponse}
import beam.router.Modes
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode._
import beam.router.RoutingModel._
import com.conveyal.r5.profile.StreetMode
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Leg
import org.matsim.core.population.routes.NetworkRoute
import org.matsim.vehicles.Vehicle
import scala.concurrent.duration._

import scala.collection.JavaConverters._


/**
  * BEAM
  */
trait ChoosesMode {
  this: PersonAgent => // Self type restricts this trait to only mix into a PersonAgent

  onTransition {
    case (PerformingActivity | Waiting) -> ChoosingMode =>
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
    case Event(MobilityStatusReponse(streetVehicles), info @ BeamAgentInfo(choosesModeData: ChoosesModeData,_)) =>
      val bodyStreetVehicle = StreetVehicle(bodyId, SpaceTime(currentActivity.getCoord, _currentTick.get.toLong), WALK, asDriver = true)
      val nextAct = nextActivity.right.get
      val departTime = DiscreteTime(_currentTick.get.toInt)
      val maybeLeg = _experiencedBeamPlan.getPlanElements.get(_experiencedBeamPlan.getPlanElements.indexOf(nextAct)-1) match {
        case l: Leg => Some(l)
        case _ => None
      }
      val modeChoiceStrategy = maybeLeg.map(l => ModeChoiceStrategy(BeamMode.withValue(l.getMode)))
      val availablePersonalStreetVehicles = modeChoiceStrategy match {
        case None | Some(ModeChoiceStrategy(CAR | BIKE | DRIVE_TRANSIT)) =>
          // In these cases, a personal vehicle will be involved
          streetVehicles.filter(_.asDriver)
        case Some(ModeChoiceStrategy(DRIVE_TRANSIT))=>
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

      // Mark rideHailingResult as None if we need to request a new one, or fake a result if we don't need to make a request
      val rideHailingResult = modeChoiceStrategy match {
        case None | Some(ModeChoiceStrategy(RIDE_HAIL)) =>
          None
        case _ =>
          Some(RideHailingInquiryResponse(Id.create[RideHailingInquiry]("NA", classOf[RideHailingInquiry]), Vector(), Some(RideHailNotRequestedError)))
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
        case Some(ModeChoiceStrategy(WALK)) =>
          makeRequestWith(Vector(), Vector(bodyStreetVehicle))
        case Some(ModeChoiceStrategy(WALK_TRANSIT)) =>
          makeRequestWith(Vector(TRANSIT), Vector(bodyStreetVehicle))
        case Some(ModeChoiceStrategy(mode @ (CAR | BIKE))) =>
          maybeLeg.map(l => (l, l.getRoute)) match {
            case Some((l, r: NetworkRoute)) =>
              val maybeVehicle = filterStreetVehiclesForQuery(streetVehicles, mode).headOption
              maybeVehicle match {
                case Some(vehicle) =>
                  val leg = BeamLeg(departTime.atTime, mode, l.getTravelTime.toLong, BeamPath((r.getStartLinkId +: r.getLinkIds.asScala :+ r.getEndLinkId).map(id => id.toString.toInt).toVector, None, SpaceTime.zero, SpaceTime.zero, 0.0))
                  router ! EmbodyWithCurrentTravelTime(leg, vehicle.id)
                case _ =>
                  makeRequestWith(Vector(), Vector(bodyStreetVehicle))
              }
            case _ =>
              makeRequestWith(Vector(), filterStreetVehiclesForQuery(streetVehicles, mode) :+ bodyStreetVehicle)
          }
        case Some(ModeChoiceStrategy(DRIVE_TRANSIT)) =>
          val LastTripIndex = currentTour.trips.size - 1
          currentTour.tripIndexOfElement(nextAct) match {
            case 0 =>
              makeRequestWith(Vector(TRANSIT), filterStreetVehiclesForQuery(streetVehicles, CAR) :+ bodyStreetVehicle)
            case LastTripIndex =>
              makeRequestWith(Vector(TRANSIT), filterStreetVehiclesForQuery(streetVehicles, CAR) :+ bodyStreetVehicle, streetVehiclesAsAccess = false)
            case _ =>
              makeRequestWith(Vector(TRANSIT), Vector(bodyStreetVehicle))
          }
        case Some(ModeChoiceStrategy(RIDE_HAIL)) =>
          makeRequestWith(Vector(), Vector(bodyStreetVehicle)) // We need a WALK alternative if RH fails
          makeRideHailRequest()
      }
      stay() using info.copy(data = choosesModeData.copy(availablePersonalStreetVehicles = availablePersonalStreetVehicles, rideHailingResult = rideHailingResult))
    /*
     * Receive and store data needed for choice.
     */
    case Event(theRouterResult: RoutingResponse, info @ BeamAgentInfo(choosesModeData: ChoosesModeData,_)) =>
      stay() using info.copy(data = choosesModeData.copy(routingResponse = Some(theRouterResult)))
    case Event(theRideHailingResult: RideHailingInquiryResponse, info @ BeamAgentInfo(choosesModeData: ChoosesModeData,_)) =>
      stay() using info.copy(data = choosesModeData.copy(rideHailingResult = Some(theRideHailingResult)))

  } using completeChoiceIfReady)

  when(WaitingForReservationConfirmation) (transform { transform {
    case Event(response@ReservationResponse(requestId, _), info @ BeamAgentInfo(wfrcData @ WaitingForReservationConfirmationData(pendingReservationConfirmation, awaitingReservationConfirmation, choosesModeData),_)) =>
      stay() using info.copy(data = wfrcData.copy(pendingReservationConfirmation = pendingReservationConfirmation - requestId, awaitingReservationConfirmation = awaitingReservationConfirmation + (requestId -> (sender(), response))))
  } using finalizeReservationsIfReady } using completeChoiceIfReady)

  case object FinishingModeChoice extends BeamAgentState

  def finalizeReservationsIfReady: PartialFunction[State, State] = {
    case s@FSM.State(stateName, info@BeamAgentInfo(wfrcData@WaitingForReservationConfirmationData(pendingReservationConfirmation, awaitingReservationConfirmation, choosesModeData), triggersToSchedule), timeout, stopReason, replies)
      if pendingReservationConfirmation.isEmpty =>
      if (awaitingReservationConfirmation.values.forall(_._2.response.isRight)) {
        val triggers = awaitingReservationConfirmation.flatMap(_._2._2.response.right.get.triggersToSchedule)
        log.debug("scheduling triggers from reservation responses: {}", triggers)
        goto(FinishingModeChoice) using info.copy(data = choosesModeData, triggersToSchedule = triggers.toVector ++ triggersToSchedule)
      } else {
        val firstErrorResponse = awaitingReservationConfirmation.values.filter(_._2.response.isLeft).head._2.response.left.get
        if (choosesModeData.routingResponse.get.itineraries.isEmpty & choosesModeData.rideHailingResult.get.error.isDefined) {
          // RideUnavailableError is defined for RHM and the trips are empty, but we don't check
          // if more agents could be hailed.
          stop(Failure(firstErrorResponse.errorCode.toString))
        } else {
          cancelTrip(stateData.data.asInstanceOf[WaitingForReservationConfirmationData].choosesModeData.pendingChosenTrip.get.legs, _currentVehicle)
          goto(ChoosingMode) using info.copy(
            data = choosesModeData.copy(
              pendingChosenTrip = None,
              rideHailingResult = choosesModeData.pendingChosenTrip.get.tripClassifier match {
                case RIDE_HAIL =>
                  Some(choosesModeData.rideHailingResult.get.copy(proposals = Vector(), error = Some(firstErrorResponse)))
                case _ =>
                  choosesModeData.rideHailingResult
              },
              routingResponse = choosesModeData.pendingChosenTrip.get.tripClassifier match {
                case RIDE_HAIL =>
                  choosesModeData.routingResponse
                case _ =>
                  Some(choosesModeData.routingResponse.get.copy(itineraries = choosesModeData.routingResponse.get.itineraries.diff(Seq(choosesModeData.pendingChosenTrip.get))))
              }
            ),
            triggersToSchedule = Vector()
          )
        }
      }

  }

  def completeChoiceIfReady: PartialFunction[State, State] = {
    case s @ FSM.State(_, info @ BeamAgentInfo(choosesModeData @ ChoosesModeData(_, None, Some(routingResponse), Some(rideHailingResult), _, _),_), _, _, _) =>
      val combinedItinerariesForChoice = rideHailingResult.proposals.flatMap(x => x.responseRideHailing2Dest.itineraries) ++ routingResponse.itineraries
      val filteredItinerariesForChoice = _experiencedBeamPlan.getStrategy(nextActivity.right.get, classOf[ModeChoiceStrategy]).map(_.asInstanceOf[ModeChoiceStrategy].mode) match {
        case Some(mode) if mode != WALK =>
          val itinsWithoutWalk = if (mode == DRIVE_TRANSIT) {
            combinedItinerariesForChoice.filter(itin => itin.tripClassifier == CAR || itin.tripClassifier == DRIVE_TRANSIT)
          } else {
            combinedItinerariesForChoice.filter(_.tripClassifier != WALK)
          }
          if (itinsWithoutWalk.nonEmpty) itinsWithoutWalk else combinedItinerariesForChoice
        case _ =>
          combinedItinerariesForChoice
      }
      modeChoiceCalculator(filteredItinerariesForChoice) match {
        case Some(chosenTrip) if RideHailingAgent.getRideHailingTrip(chosenTrip).nonEmpty =>
          val awaitingReservationConfirmation = reserveRidehailing(chosenTrip, choosesModeData)
          goto(WaitingForReservationConfirmation) using info.copy(data = WaitingForReservationConfirmationData(pendingReservationConfirmation = awaitingReservationConfirmation, awaitingReservationConfirmation = Map(), choosesModeData.copy(pendingChosenTrip = Some(chosenTrip))))
        case Some(chosenTrip) =>
          goto(FinishingModeChoice) using info.copy(data = choosesModeData.copy(pendingChosenTrip = Some(chosenTrip)))
        case None =>
          // Bad things happen but we want them to continue their day, so we signal to downstream that trip should be made to be expensive
          val originalWalkTripLeg = routingResponse.itineraries.filter(_.tripClassifier == WALK).head.legs.head
          val expensiveWalkTrip = EmbodiedBeamTrip(Vector(originalWalkTripLeg.copy(cost = BigDecimal(100.0))))
          goto(FinishingModeChoice) using info.copy(data = choosesModeData.copy(pendingChosenTrip = Some(expensiveWalkTrip)))
      }
  }

  when(FinishingModeChoice, stateTimeout = Duration.Zero) {
    case Event(StateTimeout, info@BeamAgentInfo(data: ChoosesModeData,_)) =>
      goto(Waiting) using info.copy(data = data.personData.copy(currentTrip = data.pendingChosenTrip, restOfCurrentTrip = data.pendingChosenTrip))
  }

  def reserveRidehailing(chosenTrip: EmbodiedBeamTrip, choosesModeData: ChoosesModeData) = {
    val rideHailingLeg = RideHailingAgent.getRideHailingTrip(chosenTrip)
    var awaitingReservationConfirmation = Set[Id[ReservationRequest]]()
    val departAt = DiscreteTime(rideHailingLeg.head.beamLeg.startTime.toInt)
    val rideHailingId = Id.create(choosesModeData.rideHailingResult.get.inquiryId.toString, classOf[ReservationRequest])
    rideHailingManager ! ReserveRide(choosesModeData.rideHailingResult.get.inquiryId, VehiclePersonId(bodyId, id), currentActivity.getCoord, departAt, nextActivity.right.get.getCoord)
    awaitingReservationConfirmation = awaitingReservationConfirmation + rideHailingId
    awaitingReservationConfirmation
  }

  onTransition {
    case FinishingModeChoice -> Waiting =>
      // Schedule triggers contained in reservation confirmation
      stateData.triggersToSchedule.foreach(scheduler ! _)
      unstashAll()
      scheduleDepartureWithValidatedTrip(stateData.data.asInstanceOf[ChoosesModeData])
  }

  def scheduleDepartureWithValidatedTrip(choosesModeData: ChoosesModeData) = {
    val chosenTrip = choosesModeData.pendingChosenTrip.get
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
    scheduler ! completed(triggerId = theTriggerId, schedule[PersonDepartureTrigger](chosenTrip.legs.head.beamLeg.startTime, self))
  }

}

object ChoosesMode {
  case class ChoosesModeData(personData: EmptyPersonData, pendingChosenTrip: Option[EmbodiedBeamTrip] = None,
                             routingResponse: Option[RoutingResponse] = None,
                             rideHailingResult: Option[RideHailingInquiryResponse] = None,
                             availablePersonalStreetVehicles: Vector[StreetVehicle] = Vector(),
                             expectedMaxUtilityOfLatestChoice: Option[Double] = None) extends PersonData

  case class WaitingForReservationConfirmationData(pendingReservationConfirmation: Set[Id[ReservationRequest]], awaitingReservationConfirmation: Map[Id[ReservationRequest], (ActorRef, ReservationResponse)], choosesModeData: ChoosesModeData) extends PersonData

  case class LegWithPassengerVehicle(leg: EmbodiedBeamLeg, passengerVehicle: Id[Vehicle])

}
