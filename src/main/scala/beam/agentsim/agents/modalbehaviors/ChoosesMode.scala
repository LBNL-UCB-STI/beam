package beam.agentsim.agents.modalbehaviors

import akka.actor.FSM
import beam.agentsim.Resource.CheckInResource
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents._
import beam.agentsim.agents.household.HouseholdActor.MobilityStatusInquiry.mobilityStatusInquiry
import beam.agentsim.agents.household.HouseholdActor.{MobilityStatusResponse, ReleaseVehicleReservation}
import beam.agentsim.agents.modalbehaviors.ChoosesMode._
import beam.agentsim.agents.ridehail.{RideHailInquiry, RideHailRequest, RideHailResponse}
import beam.agentsim.agents.vehicles.AccessErrorCodes.RideHailNotRequestedError
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{VehiclePersonId, _}
import beam.agentsim.events.{ModeChoiceEvent, SpaceTime}
import beam.agentsim.infrastructure.ParkingManager.{ParkingInquiry, ParkingInquiryResponse}
import beam.agentsim.infrastructure.ParkingStall
import beam.agentsim.infrastructure.ParkingStall.{Any, NoCharger, NoNeed}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.BeamRouter._
import beam.router.Modes
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode._
import beam.router.model.RoutingModel._
import beam.router.model.{BeamLeg, BeamPath, EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.r5.R5RoutingWorker
import beam.sim.population.AttributesOfIndividual
import beam.utils.plan.sampling.AvailableModeUtils._
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.api.core.v01.population.{Activity, Leg}
import org.matsim.core.population.routes.NetworkRoute
import org.matsim.vehicles.Vehicle

import scala.collection.GenTraversableOnce
import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._

/**
  * BEAM
  */
trait ChoosesMode {
  this: PersonAgent => // Self type restricts this trait to only mix into a PersonAgent
  val dummyRHVehicle =
    StreetVehicle(
      Id.create("dummyRH", classOf[Vehicle]),
      SpaceTime(0.0, 0.0, 0),
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
      val currentPersonLocation = choosesModeData.currentLocation.getOrElse(
        SpaceTime(currentActivity(choosesModeData.personData).getCoord, _currentTick.get)
      )
      val availableModes: Seq[BeamMode] = availableModesForPerson(
        matsimPlan.getPerson
      )
      // Make sure the current mode is allowable
      val correctedCurrentTourMode = choosesModeData.personData.currentTourMode match {
        case Some(mode) if availableModes.contains(mode) =>
          Some(mode)
        case _ =>
          None
      }

      val bodyStreetVehicle = StreetVehicle(
        bodyId,
        currentPersonLocation,
        WALK,
        asDriver = true
      )
      val nextAct = nextActivity(choosesModeData.personData).get
      val departTime = _currentTick.get

      val availablePersonalStreetVehicles =
        correctedCurrentTourMode match {
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
        withParking: Boolean,
        streetVehiclesIntermodalUse: IntermodalUse = Access
      ): Option[Int] = {
        router ! RoutingRequest(
          currentPersonLocation.loc,
          nextAct.getCoord,
          departTime,
          Modes.filterForTransit(transitModes),
          vehicles,
          streetVehiclesIntermodalUse,
          mustParkAtEnd = true,
          timeValueOfMoney = 3600.0 / attributes.valueOfTime
        )
        if (withParking) {
          requestParkingCost(
            nextAct.getCoord,
            nextAct.getType,
            departTime,
            nextAct.getEndTime.toInt - departTime
          )
        } else {
          None
        }
      }

      def makeRideHailRequest(): Unit = {
        val inquiry = RideHailRequest(
          RideHailInquiry,
          bodyVehiclePersonId,
          currentPersonLocation.loc,
          departTime,
          nextAct.getCoord
        )
        //        println(s"requesting: ${inquiry.requestId}")
        rideHailManager ! inquiry
      }

      def makeRideHailTransitRoutingRequest(bodyStreetVehicle: StreetVehicle): Option[Int] = {
        //TODO make ride hail wait buffer config param
        val startWithWaitBuffer = 900 + departTime
        val currentSpaceTime =
          SpaceTime(currentPersonLocation.loc, startWithWaitBuffer)
        val theRequest = RoutingRequest(
          currentSpaceTime.loc,
          nextAct.getCoord,
          startWithWaitBuffer,
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

      val hasRideHail = availableModes.contains(RIDE_HAIL)
      val willRequestDrivingRoute = streetVehicles.find(_.mode == CAR).isDefined

      var responsePlaceholders = ChoosesModeResponsePlaceholders()
      var requestId: Option[Int] = None
      var parkingRequestId: Option[Int] = None
      // Form and send requests

      correctedCurrentTourMode match {
        case None =>
          if (hasRideHail) {
            responsePlaceholders = makeResponsePlaceholders(
              withRouting = true,
              withParking = willRequestDrivingRoute,
              withRideHail = true,
              withRideHailTransit = !choosesModeData.isWithinTripReplanning
            )
            makeRideHailRequest()
            if (!choosesModeData.isWithinTripReplanning) {
              requestId = makeRideHailTransitRoutingRequest(bodyStreetVehicle)
            }
          } else {
            responsePlaceholders = makeResponsePlaceholders(withRouting = true, withParking = willRequestDrivingRoute)
            requestId = None
          }
          parkingRequestId =
            makeRequestWith(Vector(TRANSIT), streetVehicles :+ bodyStreetVehicle, withParking = willRequestDrivingRoute)
        case Some(WALK) =>
          responsePlaceholders = makeResponsePlaceholders(withRouting = true)
          makeRequestWith(Vector(), Vector(bodyStreetVehicle), withParking = false)
        case Some(WALK_TRANSIT) =>
          responsePlaceholders = makeResponsePlaceholders(withRouting = true)
          makeRequestWith(Vector(TRANSIT), Vector(bodyStreetVehicle), withParking = false)
        case Some(mode @ (CAR | BIKE)) =>
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
                  val linkIds = new ArrayBuffer[Int](2 + r.getLinkIds.size())
                  linkIds += r.getStartLinkId.toString.toInt
                  r.getLinkIds.asScala.foreach { id =>
                    linkIds += id.toString.toInt
                  }
                  linkIds += r.getEndLinkId.toString.toInt

                  val leg = BeamLeg(
                    departTime,
                    mode,
                    l.getTravelTime.toInt,
                    // TODO FIXME
                    BeamPath(linkIds, Vector.empty, None, SpaceTime.zero, SpaceTime.zero, r.getDistance)
                  )
                  router ! EmbodyWithCurrentTravelTime(leg, vehicle.id, mustParkAtEnd = true)
                  parkingRequestId = requestParkingCost(
                    leg.travelPath.endPoint.loc,
                    nextAct.getType,
                    leg.endTime,
                    nextAct.getEndTime.intValue() - leg.endTime
                  )
                  responsePlaceholders = makeResponsePlaceholders(withRouting = true, withParking = true)
                case _ =>
                  makeRequestWith(Vector(), Vector(bodyStreetVehicle), withParking = false)
                  responsePlaceholders = makeResponsePlaceholders(withRouting = true, withParking = false)
              }
            case _ =>
              parkingRequestId = makeRequestWith(
                Vector(),
                filterStreetVehiclesForQuery(streetVehicles, mode) :+ bodyStreetVehicle,
                withParking = (mode == CAR)
              )
              responsePlaceholders = makeResponsePlaceholders(withRouting = true, withParking = (mode == CAR))
          }
        case Some(DRIVE_TRANSIT) =>
          val LastTripIndex = currentTour(choosesModeData.personData).trips.size - 1
          (
            currentTour(choosesModeData.personData).tripIndexOfElement(nextAct),
            choosesModeData.personData.currentTourPersonalVehicle
          ) match {
            case (0, _) if !choosesModeData.isWithinTripReplanning =>
              // We use our car if we are not replanning, otherwise we end up doing a walk transit (catch-all below)
              // we do not send parking inquiry here, instead we wait for drive_transit route to come back and we use
              // actual location of transit station
              makeRequestWith(
                Vector(TRANSIT),
                filterStreetVehiclesForQuery(streetVehicles, CAR) :+ bodyStreetVehicle,
                withParking = false
              )
              responsePlaceholders = makeResponsePlaceholders(withRouting = true, withParking = false)
            case (LastTripIndex, Some(currentTourPersonalVehicleId)) =>
              // At the end of the tour, only drive home a vehicle that we have also taken away from there.
              parkingRequestId = makeRequestWith(
                Vector(TRANSIT),
                streetVehicles
                  .filter(_.id == currentTourPersonalVehicleId) :+ bodyStreetVehicle,
                streetVehiclesIntermodalUse = Egress,
                withParking = true
              )
              responsePlaceholders = makeResponsePlaceholders(withRouting = true, withParking = true)
            case _ =>
              makeRequestWith(Vector(TRANSIT), Vector(bodyStreetVehicle), withParking = false)
              responsePlaceholders = makeResponsePlaceholders(withRouting = true, withParking = false)
          }
        case Some(RIDE_HAIL) if choosesModeData.isWithinTripReplanning =>
          // Give up on all ride hail after a failure
          responsePlaceholders = makeResponsePlaceholders(withRouting = true)
          makeRequestWith(Vector(TRANSIT), Vector(bodyStreetVehicle), withParking = false)
        case Some(RIDE_HAIL) =>
          responsePlaceholders = makeResponsePlaceholders(withRouting = true, withRideHail = true)
          makeRequestWith(Vector(), Vector(bodyStreetVehicle), withParking = false) // We need a WALK alternative if RH fails
          makeRideHailRequest()
        case Some(RIDE_HAIL_TRANSIT) if choosesModeData.isWithinTripReplanning =>
          // Give up on ride hail transit after a failure, too complicated, but try regular ride hail again
          responsePlaceholders = makeResponsePlaceholders(withRouting = true, withRideHail = true)
          makeRequestWith(Vector(TRANSIT), Vector(bodyStreetVehicle), withParking = false)
          makeRideHailRequest()
        case Some(RIDE_HAIL_TRANSIT) =>
          responsePlaceholders = makeResponsePlaceholders(withRideHailTransit = true)
          requestId = makeRideHailTransitRoutingRequest(bodyStreetVehicle)
        case Some(m) =>
          logDebug(m.toString)
      }
      val newPersonData = choosesModeData.copy(
        personData = choosesModeData.personData.copy(currentTourMode = correctedCurrentTourMode),
        availablePersonalStreetVehicles = availablePersonalStreetVehicles,
        routingResponse = responsePlaceholders.routingResponse,
        parkingResponse = responsePlaceholders.parkingResponse,
        parkingRequestId = parkingRequestId,
        driveTransitParkingResponse = responsePlaceholders.driveTransitParkingResponse,
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
        theRouterResult @ RoutingResponse(_, _, Some(requestId)),
        choosesModeData: ChoosesModeData
        ) if choosesModeData.rideHail2TransitRoutingRequestId.contains(requestId) =>
      val driveTransitTrip =
        theRouterResult.itineraries.view
          .dropWhile(_.tripClassifier != DRIVE_TRANSIT)
          .headOption
      var newPersonData = if (driveTransitTrip.isDefined) {
        val accessLeg = driveTransitTrip.get.legs.view.takeWhile(!_.beamLeg.mode.isTransit).last.beamLeg
        val dest = accessLeg.travelPath.endPoint.loc
        val driveTransitRequestId = requestParkingCost(
          dest,
          "ParkAndRide",
          accessLeg.endTime,
          nextActivity(choosesModeData.personData).get.getEndTime.toInt - accessLeg.endTime
        )
        choosesModeData.copy(driveTransitParkingResponse = None, driveTransitParkingRequestId = driveTransitRequestId)
      } else {
        choosesModeData
      }
      // If there's a drive-transit trip AND we don't have an error RH2Tr response (due to no desire to use RH) then seek RH on access and egress
      newPersonData =
        if (shouldAttemptRideHail2Transit(
              driveTransitTrip,
              choosesModeData.rideHail2TransitAccessResult
            )) {
          val accessSegment =
            driveTransitTrip.get.legs.view
              .takeWhile(!_.beamLeg.mode.isMassTransit)
              .map(_.beamLeg)
          val egressSegment = driveTransitTrip.get.legs.view
            .dropWhile(!_.beamLeg.mode.isMassTransit)
            .dropWhile(_.beamLeg.mode.isMassTransit)
            .map(_.beamLeg)
            .headOption
          //TODO replace hard code number here with parameter
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
          newPersonData.copy(
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
          newPersonData.copy(
            rideHail2TransitRoutingResponse = Some(EmbodiedBeamTrip.empty),
            rideHail2TransitAccessResult = Some(RideHailResponse.dummyWithError(RideHailNotRequestedError)),
            rideHail2TransitEgressResult = Some(RideHailResponse.dummyWithError(RideHailNotRequestedError))
          )
        }
      stay() using newPersonData
    case Event(theRouterResult: RoutingResponse, choosesModeData: ChoosesModeData) =>
      val correctedItins = theRouterResult.itineraries.map {
        trip =>
          if (trip.legs.head.beamLeg.mode == CAR) {
            val startLeg = EmbodiedBeamLeg(
              BeamLeg.dummyWalk(trip.legs.head.beamLeg.startTime),
              bodyId,
              asDriver = true,
              None,
              0,
              unbecomeDriverOnCompletion = false
            )
            val endLeg = EmbodiedBeamLeg(
              BeamLeg.dummyWalk(trip.legs.last.beamLeg.endTime),
              bodyId,
              asDriver = true,
              None,
              0,
              unbecomeDriverOnCompletion = true
            )
            trip.copy(legs = (startLeg +: trip.legs) :+ endLeg)
          } else {
            trip
          }
      }
      stay() using choosesModeData.copy(routingResponse = Some(theRouterResult.copy(itineraries = correctedItins)))
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
      val newPersonData = Some(parkingInquiryResponse.requestId) match {
        case choosesModeData.parkingRequestId =>
          choosesModeData.copy(parkingResponse = Some(parkingInquiryResponse))
        case choosesModeData.driveTransitParkingRequestId =>
          choosesModeData.copy(driveTransitParkingResponse = Some(parkingInquiryResponse))
        case _ =>
          choosesModeData
      }
      stay using newPersonData
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

  def makeRideHailRequestFromBeamLeg(legs: Seq[BeamLeg]): Option[Int] = {
    val inquiry = RideHailRequest(
      RideHailInquiry,
      bodyVehiclePersonId,
      beamServices.geo.wgs2Utm(legs.head.travelPath.startPoint.loc),
      legs.head.startTime,
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
      // Replacing drive access leg with TNC changes the travel time.
      val extraWaitTimeBuffer = driveTransitTrip.legs.head.beamLeg.endTime - _currentTick.get -
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
                .updateStartTime(startTimeAdjustment - startTimeBufferForWaiting.intValue())
          )
        ) ++ driveTransitTrip.legs.tail
        val fullTrip = if (rideHail2TransitEgressResult.error.isEmpty) {
          accessAndTransit.dropRight(2) ++ rideHail2TransitEgressResult.travelProposal.head.responseRideHail2Dest.itineraries.head.legs
        } else {
          accessAndTransit.dropRight(1)
        }
        Some(
          EmbodiedBeamTrip(
            EmbodiedBeamLeg.dummyWalkLegAt(fullTrip.head.beamLeg.startTime, bodyId, false) +:
            fullTrip :+
            EmbodiedBeamLeg.dummyWalkLegAt(fullTrip.last.beamLeg.endTime, bodyId, true)
          )
        )
      }
    } else {
      None
    }
  }

  def requestParkingCost(destination: Coord, activityType: String, arrivalTime: Int, duration: Int): Option[Int] = {
    val inquiry = ParkingInquiry(
      id,
      destination,
      destination,
      activityType,
      8.0,
      NoNeed,
      arrivalTime,
      duration,
      Any,
      reserveStall = false
    )
    parkingManager ! inquiry
    Some(inquiry.requestId)
  }

  def addParkingCostToItins(
    itineraries: Seq[EmbodiedBeamTrip],
    parkingResponse: ParkingInquiryResponse,
    driveTransitParkingResponse: ParkingInquiryResponse
  ): Seq[EmbodiedBeamTrip] = {
    itineraries.map { itin =>
      itin.tripClassifier match {
        case CAR =>
          val newLegs = itin.legs.zipWithIndex.map {
            case (leg, i) => if (i == 2) { leg.copy(cost = parkingResponse.stall.cost) } else { leg }
          }
          itin.copy(legs = newLegs)
        case DRIVE_TRANSIT =>
          val newLegs = if (itin.legs.size > 2 && itin.legs(2).beamLeg.mode == CAR) {
            itin.legs.zipWithIndex.map {
              case (leg, i) => if (i == 2) { leg.copy(cost = driveTransitParkingResponse.stall.cost) } else { leg }
            }
          } else if (itin.legs.size > 2 && itin.legs.takeRight(2).head.beamLeg.mode == CAR) {
            itin.legs.zipWithIndex.map {
              case (leg, i) =>
                if (i == itin.legs.size - 2) { leg.copy(cost = driveTransitParkingResponse.stall.cost) } else { leg }
            }
          } else {
            itin.legs
          }
          itin.copy(legs = newLegs)
        case _ =>
          itin
      }
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
          Some(parkingResponse),
          _,
          Some(driveTransitParkingResponse),
          _,
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
      val currentPersonLocation = choosesModeData.currentLocation.getOrElse(
        SpaceTime(currentActivity(choosesModeData.personData).getCoord, _currentTick.get)
      )
      val nextAct = nextActivity(choosesModeData.personData).get
      val rideHail2TransitIinerary = createRideHail2TransitItin(
        rideHail2TransitAccessResult,
        rideHail2TransitEgressResult,
        rideHail2TransitRoutingResponse
      )
      val rideHailItinerary = rideHailResult.travelProposal match {
        case Some(travelProposal) =>
          val origLegs = travelProposal.responseRideHail2Dest.itineraries.head.legs
          val fullItin = travelProposal.responseRideHail2Dest.itineraries.head.copy(
            legs =
            EmbodiedBeamLeg
              .dummyWalkLegAt(origLegs.head.beamLeg.startTime, bodyId, false) +: origLegs :+ EmbodiedBeamLeg
              .dummyWalkLegAt(origLegs.head.beamLeg.endTime, bodyId, true)
          )
          travelProposal.poolingInfo match {
            case Some(poolingInfo) =>
              Vector(
                fullItin,
                fullItin.copy(
                  legs = fullItin.legs.map(
                    origLeg =>
                      origLeg.copy(
                        cost = origLeg.cost * poolingInfo.costFactor,
                        isPooledTrip = origLeg.isRideHail,
                        beamLeg = origLeg.beamLeg.scaleLegDuration(poolingInfo.timeFactor)
                    )
                  )
                )
              )
            case None =>
              Vector(fullItin)
          }
        case None =>
          Vector()
      }
      val combinedItinerariesForChoice = rideHailItinerary ++ addParkingCostToItins(
        routingResponse.itineraries,
        parkingResponse,
        driveTransitParkingResponse
      ) ++ rideHail2TransitIinerary.toVector
      //      val test = createRideHail2TransitItin(rideHail2TransitAccessResult, rideHail2TransitEgressResult, routingResponse)

      val availableModes: Seq[BeamMode] = availableModesForPerson(
        matsimPlan.getPerson
      )

      val filteredItinerariesForChoice = (choosesModeData.personData.currentTourMode match {
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
                trip => trip.tripClassifier == WALK_TRANSIT || trip.tripClassifier == RIDE_HAIL_TRANSIT
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
      }).filter(itin => availableModes.contains(itin.tripClassifier))

      val attributesOfIndividual =
        matsimPlan.getPerson.getCustomAttributes
          .get("beam-attributes")
          .asInstanceOf[AttributesOfIndividual]

      modeChoiceCalculator(filteredItinerariesForChoice.toIndexedSeq, attributesOfIndividual) match {
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
                    beamServices.geo.utm2Wgs(currentPersonLocation.loc),
                    beamServices.geo.utm2Wgs(nextAct.getCoord),
                    _currentTick.get,
                    bodyId,
                    beamServices
                  )
                  .legs
                  .head
            }
          val expensiveWalkTrip = EmbodiedBeamTrip(
            Vector(originalWalkTripLeg.copy(cost = 100))
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

      def availableAlternatives = {
        val theModes =
          data.routingResponse.get.itineraries.map(_.tripClassifier).distinct
        if (data.rideHailResult.isDefined && data.rideHailResult.get.error.isEmpty) {
          if (data.rideHailResult.get.travelProposal.isDefined && data.rideHailResult.get.travelProposal.get.poolingInfo.isDefined) {
            theModes :+ RIDE_HAIL :+ RIDE_HAIL_POOLED
          } else {
            theModes :+ RIDE_HAIL
          }
        } else {
          theModes
        }
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
          availableAlternatives.mkString(":"),
          data.availablePersonalStreetVehicles.nonEmpty,
          chosenTrip.legs.view.map(_.beamLeg.travelPath.distanceInM).sum,
          _experiencedBeamPlan.tourIndexOfElement(nextActivity(data.personData).get),
          chosenTrip
        )
      )

      val personalVehicleUsed = data.availablePersonalStreetVehicles.view
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
        currentTourPersonalVehicle = data.personData.currentTourPersonalVehicle.orElse(personalVehicleUsed)
      )
  }
}

object ChoosesMode {

  case class ChoosesModeData(
    personData: BasePersonData,
    currentLocation: Option[SpaceTime] = None,
    pendingChosenTrip: Option[EmbodiedBeamTrip] = None,
    routingResponse: Option[RoutingResponse] = None,
    parkingResponse: Option[ParkingInquiryResponse] = None,
    parkingRequestId: Option[Int] = None,
    driveTransitParkingResponse: Option[ParkingInquiryResponse] = None,
    driveTransitParkingRequestId: Option[Int] = None,
    rideHailResult: Option[RideHailResponse] = None,
    rideHail2TransitRoutingResponse: Option[EmbodiedBeamTrip] = None,
    rideHail2TransitRoutingRequestId: Option[Int] = None,
    rideHail2TransitAccessResult: Option[RideHailResponse] = None,
    rideHail2TransitAccessInquiryId: Option[Int] = None,
    rideHail2TransitEgressResult: Option[RideHailResponse] = None,
    rideHail2TransitEgressInquiryId: Option[Int] = None,
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
        personData = personData.copy(currentLegPassengerScheduleIndex = currentLegPassengerScheduleIndex)
      )

    override def hasParkingBehaviors: Boolean = true
  }

  case class ChoosesModeResponsePlaceholders(
    routingResponse: Option[RoutingResponse] = None,
    parkingResponse: Option[ParkingInquiryResponse] = None,
    driveTransitParkingResponse: Option[ParkingInquiryResponse] = None,
    rideHailResult: Option[RideHailResponse] = None,
    rideHail2TransitRoutingResponse: Option[EmbodiedBeamTrip] = None,
    rideHail2TransitAccessResult: Option[RideHailResponse] = None,
    rideHail2TransitEgressResult: Option[RideHailResponse] = None
  )

  def makeResponsePlaceholders(
    withRouting: Boolean = false,
    withParking: Boolean = false,
    withRideHail: Boolean = false,
    withRideHailTransit: Boolean = false
  ): ChoosesModeResponsePlaceholders = {
    ChoosesModeResponsePlaceholders(
      routingResponse = if (withRouting) {
        None
      } else {
        RoutingResponse.dummyRoutingResponse
      },
      parkingResponse = if (withParking) {
        None
      } else {
        Some(ParkingInquiryResponse(ParkingStall.emptyParkingStall, 0))
      },
      driveTransitParkingResponse = Some(ParkingInquiryResponse(ParkingStall.emptyParkingStall, 0)),
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
