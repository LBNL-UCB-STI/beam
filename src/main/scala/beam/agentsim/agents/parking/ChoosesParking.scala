package beam.agentsim.agents.parking

import akka.actor.ActorRef
import akka.pattern.pipe
import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents._
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.StartLegTrigger
import beam.agentsim.agents.parking.ChoosesParking._
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{BeamVehicle, PassengerSchedule, VehicleManager}
import beam.agentsim.events.{LeavingParkingEvent, ParkingEvent, SpaceTime}
import beam.agentsim.infrastructure.ChargingNetworkManager._
import beam.agentsim.infrastructure.ParkingInquiry.ParkingSearchMode
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.{ParkingInquiry, ParkingInquiryResponse, ParkingStall}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.BeamRouter.{RoutingRequest, RoutingResponse}
import beam.router.Modes.BeamMode.WALK
import beam.router.model.{EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.sim.common.GeoUtils
import beam.utils.logging.pattern.ask
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent
import org.matsim.core.api.experimental.events.EventsManager

import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * BEAM
  */
object ChoosesParking {
  case object ChoosingParkingSpot extends BeamAgentState
  case object ReleasingParkingSpot extends BeamAgentState
  case object ReleasingChargingPoint extends BeamAgentState
  case object ConnectingToChargingPoint extends BeamAgentState

  def handleUseParkingSpot(
    tick: Int,
    currentBeamVehicle: BeamVehicle,
    driver: Id[_],
    geo: GeoUtils,
    eventsManager: EventsManager
  ): Unit = {
    currentBeamVehicle.reservedStall.foreach { stall: ParkingStall =>
      currentBeamVehicle.useParkingStall(stall)
      val parkEvent = ParkingEvent(
        time = tick,
        stall = stall,
        locationWGS = geo.utm2Wgs(stall.locationUTM),
        vehicleId = currentBeamVehicle.id,
        driverId = driver.toString
      )
      eventsManager.processEvent(parkEvent) // nextLeg.endTime -> to fix repeated path traversal
    }
    currentBeamVehicle.setReservedParkingStall(None)
  }

  def calculateScore(
    cost: Double,
    energyCharge: Double
  ): Double = -cost - energyCharge

  def handleReleasingParkingSpot(
    tick: Int,
    currentBeamVehicle: BeamVehicle,
    energyChargedMaybe: Option[Double],
    driver: Id[_],
    parkingManager: ActorRef,
    eventsManager: EventsManager,
    triggerId: Long
  ): Unit = {
    val stallForLeavingParkingEvent = currentBeamVehicle.stall match {
      case Some(stall) =>
        parkingManager ! ReleaseParkingStall(stall, triggerId)
        currentBeamVehicle.unsetParkingStall()
        stall
      case None =>
        // This can now happen if a vehicle was charging and released the stall already
        currentBeamVehicle.lastUsedStall.get
    }
    val energyCharge: Double = energyChargedMaybe.getOrElse(0.0)
    val score = calculateScore(stallForLeavingParkingEvent.costInDollars, energyCharge)
    eventsManager.processEvent(
      LeavingParkingEvent(tick, stallForLeavingParkingEvent, score, driver.toString, currentBeamVehicle.id)
    )
  }
}

trait ChoosesParking extends {
  this: PersonAgent => // Self type restricts this trait to only mix into a PersonAgent

  // TODO This might be a violation of Actor framework. Need to verify
  var latestParkingInquiry: Option[ParkingInquiry] = None

  private def buildParkingInquiry(data: BasePersonData): ParkingInquiry = {
    val enrouteConfig = beamServices.beamConfig.beam.agentsim.agents.vehicles.enroute
    val firstLeg = data.restOfCurrentTrip.head
    val vehicleTrip = data.restOfCurrentTrip.takeWhile(_.beamVehicleId == firstLeg.beamVehicleId)
    val lastLeg = vehicleTrip.last.beamLeg
    val destinationUtm = SpaceTime(beamServices.geo.wgs2Utm(lastLeg.travelPath.endPoint.loc), lastLeg.endTime)
    val remainingTripData = calculateRemainingTripData(data)
    val reservedFor = VehicleManager.getReservedFor(currentBeamVehicle.vehicleManagerId.get).get
    val activityType = nextActivity(data).get.getType
    val enrouteInquiryMaybe: Option[ParkingInquiry] = if (currentBeamVehicle.isEV) {
      // Calculate thresholds for Enroute charging
      val totalDistance = vehicleTrip.map(_.beamLeg.travelPath.distanceInM).sum
      val refuelRequiredThresholdInMeters = totalDistance * enrouteConfig.refuelRequiredThresholdInPercent
      val noRefuelThresholdInMeters = totalDistance * enrouteConfig.noRefuelThresholdInPercent
      if (currentBeamVehicle.isRefuelNeeded(refuelRequiredThresholdInMeters, noRefuelThresholdInMeters)) {
        // Build Enroute charging inquiry
        Some(
          ParkingInquiry.init(
            destinationUtm,
            activityType,
            reservedFor,
            Some(currentBeamVehicle),
            remainingTripData,
            attributes.valueOfTime,
            nextActivity(data).map(_.getEndTime - lastLeg.endTime).getOrElse(0.0),
            searchMode = ParkingSearchMode.EnRoute,
            originUtm = Some(currentBeamVehicle.spaceTime),
            triggerId = getCurrentTriggerIdOrGenerate
          )
        )
      } else None
    } else None
    enrouteInquiryMaybe.getOrElse {
      // build destination parking/charging inquiry
      ParkingInquiry.init(
        destinationUtm,
        activityType,
        reservedFor,
        Some(currentBeamVehicle),
        remainingTripData,
        attributes.valueOfTime,
        nextActivity(data).map(_.getEndTime - lastLeg.endTime).getOrElse(0.0),
        triggerId = getCurrentTriggerIdOrGenerate
      )
    }
  }

  onTransition { case ReadyToChooseParking -> ChoosingParkingSpot =>
    val data = stateData.asInstanceOf[BasePersonData]
    latestParkingInquiry = Some(buildParkingInquiry(data))
    if (latestParkingInquiry.get.isChargingRequestOrEV)
      chargingNetworkManager ! latestParkingInquiry.get
    else
      parkingManager ! latestParkingInquiry.get
  }

  when(ConnectingToChargingPoint) {
    case _ @Event(StartingRefuelSession(tick, triggerId), data) =>
      log.debug(s"Vehicle ${currentBeamVehicle.id} started charging and it is now handled by the CNM at $tick")
      handleUseParkingSpot(tick, currentBeamVehicle, id, geo, eventsManager)
      self ! LastLegPassengerSchedule(triggerId)
      goto(DrivingInterrupted) using data
    case _ @Event(WaitingToCharge(tick, vehicleId, triggerId), data) =>
      log.debug(s"Vehicle $vehicleId is waiting in line and it is now handled by the CNM at $tick")
      self ! LastLegPassengerSchedule(triggerId)
      goto(DrivingInterrupted) using data
  }

  when(ReleasingChargingPoint) {
    case Event(TriggerWithId(StartLegTrigger(_, _), _), data) =>
      stash()
      stay using data
    case Event(UnhandledVehicle(tick, vehicleId, triggerId), data) =>
      assume(
        vehicleId == currentBeamVehicle.id,
        s"Agent tried to disconnect a vehicle $vehicleId that's not the current beamVehicle ${currentBeamVehicle.id}"
      )
      log.error(
        s"Vehicle $vehicleId is not handled by the CNM at tick $tick. Something is broken." +
        s"the agent will now disconnect the vehicle ${currentBeamVehicle.id} to let the simulation continue!"
      )
      handleReleasingParkingSpot(tick, currentBeamVehicle, None, id, parkingManager, eventsManager, triggerId)
      goto(ReleasingParkingSpot) using data
    case Event(UnpluggingVehicle(tick, energyCharged, triggerId), data) =>
      log.debug(s"Vehicle ${currentBeamVehicle.id} ended charging and it is not handled by the CNM at tick $tick")
      handleReleasingParkingSpot(
        tick,
        currentBeamVehicle,
        Some(energyCharged),
        id,
        parkingManager,
        eventsManager,
        triggerId
      )
      goto(WaitingToDrive) using data
  }

  when(ReleasingParkingSpot, stateTimeout = Duration.Zero) {
    case Event(TriggerWithId(StartLegTrigger(_, _), _), data) =>
      stash()
      stay using data
    case Event(StateTimeout, data: BasePersonData) =>
      val (tick, triggerId) = releaseTickAndTriggerId()
      if (currentBeamVehicle.isConnectedToChargingPoint()) {
        log.debug("Sending ChargingUnplugRequest to ChargingNetworkManager at {}", tick)
        chargingNetworkManager ! ChargingUnplugRequest(
          tick,
          currentBeamVehicle,
          triggerId
        )
        goto(ReleasingChargingPoint) using data
      } else {
        handleReleasingParkingSpot(tick, currentBeamVehicle, None, id, parkingManager, eventsManager, triggerId)
        goto(WaitingToDrive) using data
      }
    case Event(StateTimeout, data) =>
      val stall = currentBeamVehicle.stall.get
      parkingManager ! ReleaseParkingStall(stall, getCurrentTriggerIdOrGenerate)
      currentBeamVehicle.unsetParkingStall()
      releaseTickAndTriggerId()
      goto(WaitingToDrive) using data
  }

  when(ChoosingParkingSpot) {
    case Event(ParkingInquiryResponse(stall, _, _), data) =>
      val distanceThresholdToIgnoreWalking =
        beamServices.beamConfig.beam.agentsim.thresholdForWalkingInMeters
      val distanceForEnrouteCharging =
        beamServices.beamConfig.beam.agentsim.agents.vehicles.enroute.thresholdForNotWalkingToDestinationInMeters
      val enrouteMaxDuration = beamServices.beamConfig.beam.agentsim.agents.vehicles.enroute.maxDurationInSeconds
      val chargingPointMaybe = stall.chargingPointType
      val nextLeg =
        data.passengerSchedule.schedule.keys.drop(data.currentLegPassengerScheduleIndex).head
      currentBeamVehicle.setReservedParkingStall(Some(stall))
      val distance =
        beamServices.geo.distUTMInMeters(stall.locationUTM, beamServices.geo.wgs2Utm(nextLeg.travelPath.endPoint.loc))
      // If the stall is co-located with our destination... then continue on but add the stall to PersonData
      if (distance <= distanceThresholdToIgnoreWalking) {
        val (_, triggerId) = releaseTickAndTriggerId()
        scheduler ! CompletionNotice(
          triggerId,
          Vector(ScheduleTrigger(StartLegTrigger(nextLeg.startTime, nextLeg), self))
        )

        goto(WaitingToDrive) using data
      } else {
        // Else the stall requires a diversion in travel, calc the new routes (in-vehicle to the stall and walking to the destination)
        import context.dispatcher
        val currentPoint = nextLeg.travelPath.startPoint
        val currentLocUTM = beamServices.geo.wgs2Utm(currentPoint.loc)
        val currentPointUTM = currentPoint.copy(loc = currentLocUTM)
        val finalPoint = nextLeg.travelPath.endPoint

        val streetVehicle = currentBeamVehicle.toStreetVehicle
        // get route from customer to stall, add body for backup in case car route fails
        val carStreetVeh =
          StreetVehicle(
            currentBeamVehicle.id,
            currentBeamVehicle.beamVehicleType.id,
            currentPointUTM,
            streetVehicle.mode,
            asDriver = true,
            streetVehicle.needsToCalculateCost
          )
        val bodyStreetVeh =
          StreetVehicle(
            body.id,
            body.beamVehicleType.id,
            currentPointUTM,
            WALK,
            asDriver = true,
            needsToCalculateCost = false
          )
        val veh2StallRequest = RoutingRequest(
          currentLocUTM,
          stall.locationUTM,
          currentPoint.time,
          withTransit = false,
          Some(id),
          Vector(carStreetVeh, bodyStreetVeh),
          Some(attributes),
          triggerId = getCurrentTriggerIdOrGenerate
        )
        val futureVehicle2StallResponse = router ? veh2StallRequest

        // if is specifically Enroute charging
        val isEnroute = latestParkingInquiry.exists(_.searchMode == ParkingSearchMode.EnRoute)
        // or distance is greater than threshold
        // and parking duration is much greater than enrouteMaxDuration TODO: consider a buffer period later on
        // and stall has a fast charger
        val parkingDuration = latestParkingInquiry.map(_.parkingDuration).getOrElse(0.0)
        val isDistanceGreaterThanThreshold = distance > distanceForEnrouteCharging
        val isParkingDurationSmallEnoughFor = parkingDuration > enrouteMaxDuration * 2.0
        val isStallHasFastCharger = chargingPointMaybe.exists(ChargingPointType.isFastCharger)
        val isDestinationChargeTurningToAnEnrouteCharge =
          isDistanceGreaterThanThreshold && isParkingDurationSmallEnoughFor && isStallHasFastCharger

        val carIfEnroute = if (isEnroute || isDestinationChargeTurningToAnEnrouteCharge) {
          // get car route from stall to destination, TODO note we give a dummy start time and update later based on drive time to stall
          Vector(
            StreetVehicle(
              currentBeamVehicle.id,
              currentBeamVehicle.beamVehicleType.id,
              SpaceTime(stall.locationUTM, currentPoint.time),
              streetVehicle.mode,
              asDriver = true,
              streetVehicle.needsToCalculateCost
            )
          )
        } else Vector()

        // get walk route from stall to destination, note we give a dummy start time and update later based on drive time to stall
        val bodyStreetVehToDest = StreetVehicle(
          body.id,
          body.beamVehicleType.id,
          SpaceTime(stall.locationUTM, currentPoint.time),
          WALK,
          asDriver = true,
          needsToCalculateCost = false
        )

        val futureStall2DestinationResponse = router ? RoutingRequest(
          stall.locationUTM,
          beamServices.geo.wgs2Utm(finalPoint.loc),
          currentPoint.time,
          withTransit = false,
          Some(id),
          carIfEnroute :+ bodyStreetVehToDest,
          Some(attributes),
          triggerId = getCurrentTriggerIdOrGenerate
        )
        val responses = for {
          vehicle2StallResponse     <- futureVehicle2StallResponse.mapTo[RoutingResponse]
          stall2DestinationResponse <- futureStall2DestinationResponse.mapTo[RoutingResponse]
        } yield (vehicle2StallResponse, stall2DestinationResponse)
        responses pipeTo self
        stay using data
      }
    case Event(
          (vehicle2StallResponse: RoutingResponse, stall2DestinationResponse: RoutingResponse),
          data: BasePersonData
        ) =>
      // TODO IF ENROUTE CHARGE (which can be identifitied from second routingResponse2, if it has car then enroute, if walk then not)
      // TODO keep in mind that routingResponse2 can have walk or (car + walk).
      // TODO CREATE AN ACTIVITY AT PARKING STALL AND CALL IT CHARGING
      // TODO CREATE A LEG ROUTE VEHICLE TO CHARGING ACTIVITY AND A LEG ROUTE FROM CHARGING TO DESTINATION
      // TODO UPDATE PERSON DATA WITH NEW LEGS AND ACTIVITY

      isEnrouteRefueling = false
      val (tick, triggerId) = releaseTickAndTriggerId()
      val nextLeg = data.passengerSchedule.schedule.keys.drop(data.currentLegPassengerScheduleIndex).head

      val vehicleMode = currentBeamVehicle.toStreetVehicle.mode
      // If no vehicle leg returned, use previous route to destination (i.e. assume parking is at dest)
      val leg1 = if (!vehicle2StallResponse.itineraries.exists(_.tripClassifier == vehicleMode)) {
        logDebug(s"no vehicle leg ($vehicleMode) returned by router, assuming parking spot is at destination")
        EmbodiedBeamLeg(
          nextLeg,
          data.currentVehicle.head,
          body.beamVehicleType.id,
          asDriver = true,
          0.0,
          unbecomeDriverOnCompletion = true
        )
      } else {
        isEnrouteRefueling = true
        vehicle2StallResponse.itineraries.view
          .filter(_.tripClassifier == vehicleMode)
          .head
          .legs
          .view
          .filter(_.beamLeg.mode == vehicleMode)
          .head
      }

      var leg2 = if (!stall2DestinationResponse.itineraries.exists(_.tripClassifier == vehicleMode)) {
        logDebug(s"no vehicle leg ($vehicleMode) returned by router, assuming parking spot is at destination")
        isEnrouteRefueling = false
        stall2DestinationResponse.itineraries.head.legs.head
      } else {
        isEnrouteRefueling = true
        stall2DestinationResponse.itineraries.view
          .filter(_.tripClassifier == vehicleMode)
          .head
          .legs
          .view
          .filter(_.beamLeg.mode == vehicleMode)
          .head
      }
      // Update start time of the second leg
      leg2 = leg2.copy(beamLeg = leg2.beamLeg.updateStartTime(leg1.beamLeg.endTime))

      // update person data with new legs
      val firstLeg = data.restOfCurrentTrip.head
      var legsToDrop = data.restOfCurrentTrip.takeWhile(_.beamVehicleId == firstLeg.beamVehicleId)
      if (legsToDrop.size == data.restOfCurrentTrip.size - 1) legsToDrop = data.restOfCurrentTrip
      val newRestOfTrip = leg1 +: (leg2 +: data.restOfCurrentTrip.filter { leg =>
        !legsToDrop.exists(dropLeg => dropLeg.beamLeg == leg.beamLeg)
      }).toVector
      val newCurrentTripLegs = data.currentTrip.get.legs.takeWhile(_.beamLeg != nextLeg) ++ newRestOfTrip
      val newPassengerSchedule = PassengerSchedule().addLegs(Vector(newRestOfTrip.head.beamLeg))

      val newVehicle = if (leg1.beamLeg.mode == vehicleMode || currentBeamVehicle.id == body.id) {
        data.currentVehicle
      } else {
        currentBeamVehicle.unsetDriver()
        eventsManager.processEvent(
          new PersonLeavesVehicleEvent(tick, id, data.currentVehicle.head)
        )
        data.currentVehicle.drop(1)
      }

      scheduler ! CompletionNotice(
        triggerId,
        Vector(
          ScheduleTrigger(
            StartLegTrigger(newRestOfTrip.head.beamLeg.startTime, newRestOfTrip.head.beamLeg),
            self
          )
        )
      )

      goto(WaitingToDrive) using data.copy(
        currentTrip = Some(EmbodiedBeamTrip(newCurrentTripLegs)),
        restOfCurrentTrip = newRestOfTrip.toList,
        passengerSchedule = newPassengerSchedule,
        currentLegPassengerScheduleIndex = 0,
        currentVehicle = newVehicle
      )

//      if (
//        stall2DestinationResponse.itineraries.headOption.exists(_.tripClassifier == CAR) && data.enrouteCharging.isEmpty
//      ) {
//        val path = stall2DestinationResponse.itineraries.head.beamLegs.find(_.mode == CAR).get.travelPath
//        val linkId = Id.createLinkId(path.linkIds.head)
//        val coord = beamServices.geo.wgs2Utm(path.startPoint.loc)
//        val activity = PopulationUtils.createActivityFromCoordAndLinkId("charging", coord, linkId)
//        val leg = PopulationUtils.createLeg("car")
//        _experiencedBeamPlan.updatePlanAfter(activity, leg, data.currentActivityIndex)
//        updatedData = updatedData.copy(enrouteCharging = Some(()))
//      }

  }
}
