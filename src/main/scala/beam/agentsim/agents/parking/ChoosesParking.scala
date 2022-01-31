package beam.agentsim.agents.parking

import akka.actor.ActorRef
import akka.pattern.pipe
import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents._
import beam.agentsim.agents.freight.FreightRequestType
import beam.agentsim.agents.freight.input.FreightReader.FREIGHT_REQUEST_TYPE
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.StartLegTrigger
import beam.agentsim.agents.parking.ChoosesParking._
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{BeamVehicle, PassengerSchedule, VehicleCategory, VehicleManager}
import beam.agentsim.events.{LeavingParkingEvent, ParkingEvent, SpaceTime}
import beam.agentsim.infrastructure.ChargingNetworkManager._
import beam.agentsim.infrastructure.charging.{ChargingPointType, ElectricCurrentType}
import beam.agentsim.infrastructure.parking.PricingModel
import beam.agentsim.infrastructure.taz.TAZTreeMap
import beam.agentsim.infrastructure.{ParkingInquiry, ParkingInquiryResponse, ParkingStall}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.BeamRouter.{RoutingRequest, RoutingResponse}
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.WALK
import beam.router.model.{EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.skim.core.ParkingSkimmer.ChargerType
import beam.router.skim.event.{FreightSkimmerEvent, ParkingSkimmerEvent}
import beam.sim.common.GeoUtils
import beam.utils.logging.pattern.ask
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent
import org.matsim.api.core.v01.population.Activity
import org.matsim.core.api.experimental.events.EventsManager
import org.slf4j.LoggerFactory

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
  private val logger = LoggerFactory.getLogger(this.getClass)

  def handleUseParkingSpot(
    tick: Int,
    currentBeamVehicle: BeamVehicle,
    driver: Id[_],
    geo: GeoUtils,
    eventsManager: EventsManager,
    tazTreeMap: TAZTreeMap,
    nextActivity: Option[Activity],
    trip: Option[EmbodiedBeamTrip],
    restOfTrip: Option[List[EmbodiedBeamLeg]]
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
      restOfTrip.foreach { legs =>
        if (legs.size >= 2 && legs(0).beamLeg.mode == BeamMode.CAR && legs(1).beamLeg.mode == BeamMode.WALK) {
          val parkingSkimmerEvent = createParkingSkimmerEvent(tick, tazTreeMap, nextActivity, stall, legs)
          eventsManager.processEvent(parkingSkimmerEvent)
          val freightRequestType =
            nextActivity.flatMap(activity =>
              Option(activity.getAttributes.getAttribute(FREIGHT_REQUEST_TYPE)).asInstanceOf[Option[FreightRequestType]]
            )
          freightRequestType.foreach { requestType =>
            val freightSkimmerEvent = createFreightSkimmerEvent(tick, trip, parkingSkimmerEvent, requestType)
            eventsManager.processEvent(freightSkimmerEvent)
          }
        }
      }
    }
    currentBeamVehicle.setReservedParkingStall(None)
  }

  private def createFreightSkimmerEvent(
    tick: Int,
    trip: Option[EmbodiedBeamTrip],
    parkingSkimmerEvent: ParkingSkimmerEvent,
    requestType: FreightRequestType
  ) = {
    val (loading, unloading) = requestType match {
      case FreightRequestType.Unloading => (0, 1)
      case FreightRequestType.Loading   => (1, 0)
    }
    val costPerMile = trip
      .map { trip =>
        val carDistanceInM = trip.beamLegs.collect {
          case leg if leg.mode == BeamMode.CAR => leg.travelPath.distanceInM
        }.sum
        logger.info("Cost: {}, dist: {}", trip.costEstimate, carDistanceInM)
        trip.costEstimate / carDistanceInM * 1609.0
      }
      .getOrElse(Double.NaN)
    val freightSkimmerEvent = new FreightSkimmerEvent(
      tick,
      parkingSkimmerEvent.tazId,
      loading,
      unloading,
      costPerMile,
      parkingSkimmerEvent.walkAccessDistanceInM,
      parkingSkimmerEvent.parkingCostPerHour
    )
    freightSkimmerEvent
  }

  private def createParkingSkimmerEvent(
    tick: Int,
    tazTreeMap: TAZTreeMap,
    nextActivity: Option[Activity],
    stall: ParkingStall,
    restOfTrips: List[EmbodiedBeamLeg]
  ): ParkingSkimmerEvent = {
    val walkLeg = restOfTrips(1)
    val tazId = tazTreeMap.getTAZ(walkLeg.beamLeg.travelPath.endPoint.loc).tazId
    val chargerType = stall.chargingPointType match {
      case Some(chargingType) if ChargingPointType.getChargingPointCurrent(chargingType) == ElectricCurrentType.DC =>
        ChargerType.DCFastCharger
      case Some(_) => ChargerType.ACSlowCharger
      case None    => ChargerType.NoCharger
    }
    val parkingCostPerHour = (stall.pricingModel, nextActivity) match {
      case (Some(PricingModel.Block(costInDollars, intervalSeconds)), _) => costInDollars / intervalSeconds * 3600
      case (Some(PricingModel.FlatFee(costInDollars)), Some(activity))
          if activity.getEndTime - activity.getStartTime > 0 =>
        costInDollars / (activity.getEndTime - activity.getStartTime) * 3600
      case (Some(PricingModel.FlatFee(costInDollars)), _) => costInDollars
      case (None, _)                                      => 0
    }
    val walkAccessDistance = walkLeg.beamLeg.travelPath.distanceInM
    val parkingSkimmerEvent = new ParkingSkimmerEvent(
      tick,
      tazId,
      chargerType,
      walkAccessDistance,
      parkingCostPerHour
    )
    parkingSkimmerEvent
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
  onTransition { case ReadyToChooseParking -> ChoosingParkingSpot =>
    val personData = stateData.asInstanceOf[BasePersonData]

    val firstLeg = personData.restOfCurrentTrip.head
    val lastLeg = personData.restOfCurrentTrip.takeWhile(_.beamVehicleId == firstLeg.beamVehicleId).last

    val parkingDuration: Double = {
      for {
        act <- nextActivity(personData)
        lastLegEndTime = lastLeg.beamLeg.endTime.toDouble
      } yield act.getEndTime - lastLegEndTime
    }.getOrElse(0.0)
    val destinationUtm = beamServices.geo.wgs2Utm(lastLeg.beamLeg.travelPath.endPoint.loc)

    // in meter (the distance that should be considered as buffer for range estimation

    val nextActivityType = nextActivity(personData).get.getType

    val remainingTripData = calculateRemainingTripData(personData)

    val parkingInquiry = ParkingInquiry.init(
      SpaceTime(destinationUtm, lastLeg.beamLeg.endTime),
      nextActivityType,
      VehicleManager.getReservedFor(currentBeamVehicle.vehicleManagerId.get).get,
      Some(this.currentBeamVehicle),
      remainingTripData,
      Some(this.id),
      attributes.valueOfTime,
      parkingDuration,
      triggerId = getCurrentTriggerIdOrGenerate
    )

    park(parkingInquiry)
  }

  when(ConnectingToChargingPoint) {
    case _ @Event(StartingRefuelSession(tick, triggerId), data) =>
      log.debug(s"Vehicle ${currentBeamVehicle.id} started charging and it is now handled by the CNM at $tick")
      val maybePersonData = findPersonData(data)
      val maybeNextActivity = maybePersonData.flatMap(nextActivity)
      val trip = maybePersonData.flatMap(_.currentTrip)
      val restOfTrip = maybePersonData.map(_.restOfCurrentTrip)
      handleUseParkingSpot(
        tick,
        currentBeamVehicle,
        id,
        geo,
        eventsManager,
        beamScenario.tazTreeMap,
        maybeNextActivity,
        trip,
        restOfTrip
      )
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
      goto(WaitingToDrive) using data
    case Event(UnpluggingVehicle(tick, energyCharged, triggerId), data) =>
      log.debug(s"Vehicle ${currentBeamVehicle.id} ended charging and it is not handled by the CNM at tick $tick")
      val energyMaybe = Some(energyCharged)
      handleReleasingParkingSpot(tick, currentBeamVehicle, energyMaybe, id, parkingManager, eventsManager, triggerId)
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

        // get walk route from stall to destination, note we give a dummy start time and update later based on drive time to stall
        val futureStall2DestinationResponse = router ? RoutingRequest(
          stall.locationUTM,
          beamServices.geo.wgs2Utm(finalPoint.loc),
          currentPoint.time,
          withTransit = false,
          Some(id),
          Vector(
            StreetVehicle(
              body.id,
              body.beamVehicleType.id,
              SpaceTime(stall.locationUTM, currentPoint.time),
              WALK,
              asDriver = true,
              needsToCalculateCost = false
            )
          ),
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
    case Event((routingResponse1: RoutingResponse, routingResponse2: RoutingResponse), data: BasePersonData) =>
      val (tick, triggerId) = releaseTickAndTriggerId()
      val nextLeg =
        data.passengerSchedule.schedule.keys.drop(data.currentLegPassengerScheduleIndex).head

      val vehicleMode = currentBeamVehicle.toStreetVehicle.mode
      // If no vehicle leg returned, use previous route to destination (i.e. assume parking is at dest)
      var (leg1, leg2) = if (!routingResponse1.itineraries.exists(_.tripClassifier == vehicleMode)) {
        logDebug(s"no vehicle leg ($vehicleMode) returned by router, assuming parking spot is at destination")
        (
          EmbodiedBeamLeg(
            nextLeg,
            data.currentVehicle.head,
            body.beamVehicleType.id,
            asDriver = true,
            0.0,
            unbecomeDriverOnCompletion = true
          ),
          routingResponse2.itineraries.head.legs.head
        )
      } else {
        (
          routingResponse1.itineraries.view
            .filter(_.tripClassifier == vehicleMode)
            .head
            .legs
            .view
            .filter(_.beamLeg.mode == vehicleMode)
            .head,
          routingResponse2.itineraries.head.legs.head
        )
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
      val newCurrentTripLegs = data.currentTrip.get.legs
        .takeWhile(_.beamLeg != nextLeg) ++ newRestOfTrip
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
  }
}
