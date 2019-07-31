package beam.agentsim.agents.parking

import akka.pattern.{ask, pipe}
import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents._
import beam.agentsim.agents.choice.logit.{MultinomialLogit, UtilityFunctionOperation}
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.{EndRefuelSessionTrigger, StartLegTrigger}
import beam.agentsim.agents.parking.ChoosesParking.{ChoosingParkingSpot, ReleasingParkingSpot}
import beam.agentsim.agents.vehicles.PassengerSchedule
import beam.agentsim.agents.vehicles.FuelType.{Electricity, Gasoline}
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, PassengerSchedule}
import beam.agentsim.events.{
  ChargingPlugInEvent,
  ChargingPlugOutEvent,
  LeavingParkingEvent,
  RefuelSessionEvent,
  SpaceTime
}

import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.BeamRouter.{RoutingRequest, RoutingResponse}
import beam.router.BeamSkimmer
import beam.router.Modes.BeamMode.{CAR, WALK}
import beam.router.model.{EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.sim.common.GeoUtils
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent

import scala.concurrent.duration.Duration
import beam.agentsim.infrastructure.parking.{ParkingType, ParkingZone, ParkingZoneSearch}
import beam.agentsim.infrastructure.taz.TAZ
import beam.agentsim.infrastructure.{ParkingInquiry, ParkingInquiryResponse, ParkingStall}
import beam.utils.ParkingManagerIdGenerator
import org.matsim.api.core.v01.Coord

/**
  * BEAM
  */
object ChoosesParking {

  case object ChoosingParkingSpot extends BeamAgentState

  case object ReleasingParkingSpot extends BeamAgentState

}

trait ChoosesParking extends {
  this: PersonAgent => // Self type restricts this trait to only mix into a PersonAgent

  onTransition {
    case ReadyToChooseParking -> ChoosingParkingSpot =>
      val personData = stateData.asInstanceOf[BasePersonData]

      val firstLeg = personData.restOfCurrentTrip.head
      val lastLeg =
        personData.restOfCurrentTrip.takeWhile(_.beamVehicleId == firstLeg.beamVehicleId).last

      val parkingDuration: Double = {
        for {
          act <- nextActivity(personData)
          lastLegEndTime = lastLeg.beamLeg.endTime.toDouble
        } yield act.getEndTime - lastLegEndTime
      }.getOrElse(0.0)
      val destinationUtm = beamServices.geo.wgs2Utm(lastLeg.beamLeg.travelPath.endPoint.loc)

      // distance to walk to the destination
      val distanceFactor =
        beamScenario.beamConfig.beam.agentsim.agents.parking.mulitnomialLogit.params.distance_multiplier
      // installed charging capacity
      val installedCapacityFactor =
        beamScenario.beamConfig.beam.agentsim.agents.parking.mulitnomialLogit.params.installed_capacity_multiplier
      // parking costs (currently include price for charging due to the lack of data)
      val parkingCostsPriceFactor =
        beamScenario.beamConfig.beam.agentsim.agents.parking.mulitnomialLogit.params.parking_costs_price_multiplier
      // in meter (the distance that should be considered as buffer for range estimation
      val distanceSafetyMargin = beamScenario.beamConfig.beam.agentsim.agents.parking.bev.distance_safety_margin
      var nextActivityType = nextActivity(personData).get.getType

      val utilityFunction: MultinomialLogit[ParkingZoneSearch.ParkingAlternative, String] =
        (currentBeamVehicle.beamVehicleType.primaryFuelType, currentBeamVehicle.beamVehicleType.secondaryFuelType) match {
          case (Electricity, None) => { //BEV
            //calculate the remaining driving distance with the current SoC in meters, reduced by a safety marking (distance buffer)
            val remainingDrivingDist = (beamScenario
              .privateVehicles(personData.currentVehicle.head)
              .primaryFuelLevelInJoules / currentBeamVehicle.beamVehicleType.primaryFuelConsumptionInJoulePerMeter) - distanceSafetyMargin

            val remainingTourDist = nextActivity(personData) match {
              case Some(nextAct) =>
                val nextActIdx = currentTour(personData).tripIndexOfElement(nextAct) - 1
                currentTour(personData).trips
                  .slice(nextActIdx, currentTour(personData).trips.length)
                  .sliding(2, 1)
                  .toList
                  .foldLeft(0d) { (sum, pair) =>
                    sum + Math
                      .ceil(
                        beamSkimmer
                          .getTimeDistanceAndCost(
                            pair.head.activity.getCoord,
                            pair.last.activity.getCoord,
                            0,
                            CAR,
                            currentBeamVehicle.beamVehicleType.id
                          )
                          .distance
                      )
                  }
              case None =>
                0 // if we don't have any more trips we don't need a chargingInquiry as we are @home again => assumption: charging @home always takes place
            }

            remainingTourDist match {
              case 0 =>
                nextActivityType = "charge"
                new MultinomialLogit(Map.empty, Map.empty) //@home
              // must -> walking distance doesn't matter as we really NEED TO CHARGE
              case _ if remainingDrivingDist <= remainingTourDist =>
                nextActivityType = "charge"
                new MultinomialLogit(
                  Map.empty,
                  Map(
                    "distanceFactor"          -> UtilityFunctionOperation("multiplier", 0),
                    "installedCapacity"       -> UtilityFunctionOperation("multiplier", installedCapacityFactor),
                    "parkingCostsPriceFactor" -> UtilityFunctionOperation("multiplier", -parkingCostsPriceFactor),
                  )
                )
              // opportunistic
              case _ if remainingDrivingDist > remainingTourDist =>
                new MultinomialLogit(
                  Map.empty,
                  Map(
                    "distanceFactor"          -> UtilityFunctionOperation("multiplier", -distanceFactor),
                    "installedCapacity"       -> UtilityFunctionOperation("multiplier", installedCapacityFactor),
                    "parkingCostsPriceFactor" -> UtilityFunctionOperation("multiplier", -parkingCostsPriceFactor),
                  )
                )
            }
          }
          case (Electricity, Some(Gasoline)) => { // PHEV
            new MultinomialLogit(
              Map.empty,
              Map(
                "distanceFactor"          -> UtilityFunctionOperation("multiplier", -distanceFactor),
                "installedCapacity"       -> UtilityFunctionOperation("multiplier", installedCapacityFactor),
                "parkingCostsPriceFactor" -> UtilityFunctionOperation("multiplier", -parkingCostsPriceFactor),
              )
            ) // PHEV is always opportunistic
          }
          case _ =>
            // non BEV / PHEV, installed charging capacity doesn't matter
            new MultinomialLogit(
              Map.empty,
              Map(
                "distanceFactor"          -> UtilityFunctionOperation("multiplier", -distanceFactor),
                "installedCapacity"       -> UtilityFunctionOperation("multiplier", 0),
                "parkingCostsPriceFactor" -> UtilityFunctionOperation("multiplier", -parkingCostsPriceFactor),
              )
            )
        }

      parkingManager ! ParkingInquiry(
        destinationUtm,
        nextActivityType,
        attributes.valueOfTime,
        utilityFunction,
        parkingDuration,
        this.currentTourBeamVehicle
      )
  }
  when(ReleasingParkingSpot, stateTimeout = Duration.Zero) {
    case Event(TriggerWithId(StartLegTrigger(_, _), _), data) =>
      stash()
      stay using data
    case Event(StateTimeout, data: BasePersonData) =>
      val (tick, _) = releaseTickAndTriggerId()
      val stall = currentBeamVehicle.stall.getOrElse {
        val theVehicle = currentBeamVehicle
        throw new RuntimeException(log.format("My vehicle {} is not parked.", currentBeamVehicle.id))
      }

      if (currentBeamVehicle.isConnectedToChargingPoint()) {
        handleEndCharging(tick, currentBeamVehicle)
      }

      parkingManager ! ReleaseParkingStall(stall.parkingZoneId)
      val nextLeg = data.passengerSchedule.schedule.head._1
      val distance = beamServices.geo.distUTMInMeters(stall.locationUTM, nextLeg.travelPath.endPoint.loc)
      val energyCharge: Double = 0.0 //TODO
      val timeCost
        : Double = 0.0 //scaleTimeByValueOfTime(0.0) // TODO: CJRS... let's discuss how to fix this - SAF,  ZN UPDATE: Also need to change VOT function
      val score = calculateScore(distance, stall.cost, energyCharge, timeCost)
      eventsManager.processEvent(LeavingParkingEvent(tick, stall, score, id, currentBeamVehicle.id))
      currentBeamVehicle.unsetParkingStall()
      goto(WaitingToDrive) using data

    case Event(StateTimeout, data) =>
      parkingManager ! ReleaseParkingStall(currentBeamVehicle.stall.get.parkingZoneId)
      currentBeamVehicle.unsetParkingStall()
      releaseTickAndTriggerId()
      goto(WaitingToDrive) using data
  }
  when(ChoosingParkingSpot) {
    case Event(ParkingInquiryResponse(stall, _), data) =>
      val distanceThresholdToIgnoreWalking =
        beamServices.beamConfig.beam.agentsim.thresholdForWalkingInMeters
      val nextLeg =
        data.passengerSchedule.schedule.keys.drop(data.currentLegPassengerScheduleIndex).head
      currentBeamVehicle.setReservedParkingStall(Some(stall))

      // data.currentVehicle.head

      //Veh id
      //distance to dest
      //parking Id
      //cost
      //location

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

        // get route from customer to stall, add body for backup in case car route fails
        val carStreetVeh =
          StreetVehicle(
            currentBeamVehicle.id,
            currentBeamVehicle.beamVehicleType.id,
            currentPointUTM,
            CAR,
            asDriver = true
          )
        val bodyStreetVeh =
          StreetVehicle(body.id, body.beamVehicleType.id, currentPointUTM, WALK, asDriver = true)
        val veh2StallRequest = RoutingRequest(
          currentLocUTM,
          stall.locationUTM,
          currentPoint.time,
          withTransit = false,
          Vector(carStreetVeh, bodyStreetVeh),
          Some(attributes)
        )
        val futureVehicle2StallResponse = router ? veh2StallRequest

        // get walk route from stall to destination, note we give a dummy start time and update later based on drive time to stall
        val futureStall2DestinationResponse = router ? RoutingRequest(
          stall.locationUTM,
          beamServices.geo.wgs2Utm(finalPoint.loc),
          currentPoint.time,
          withTransit = false,
          Vector(
            StreetVehicle(
              body.id,
              body.beamVehicleType.id,
              SpaceTime(stall.locationUTM, currentPoint.time),
              WALK,
              asDriver = true
            )
          ),
          Some(attributes)
        )

        val responses = for {
          vehicle2StallResponse     <- futureVehicle2StallResponse.mapTo[RoutingResponse]
          stall2DestinationResponse <- futureStall2DestinationResponse.mapTo[RoutingResponse]
        } yield (vehicle2StallResponse, stall2DestinationResponse)

        responses pipeTo self
        stay using data
      }
    case Event(
        (routingResponse1: RoutingResponse, routingResponse2: RoutingResponse),
        data: BasePersonData
        ) =>
      val (tick, triggerId) = releaseTickAndTriggerId()
      val nextLeg =
        data.passengerSchedule.schedule.keys.drop(data.currentLegPassengerScheduleIndex).head

      // If no car leg returned, use previous route to destination (i.e. assume parking is at dest)
      var (leg1, leg2) = if (!routingResponse1.itineraries.exists(_.tripClassifier == CAR)) {
        logDebug(s"no CAR leg returned by router, assuming parking spot is at destination")
        (
          EmbodiedBeamLeg(
            nextLeg,
            data.currentVehicle.head,
            body.beamVehicleType.id,
            true,
            0.0,
            true
          ),
          routingResponse2.itineraries.head.legs.head
        )
      } else {
        (
          routingResponse1.itineraries.view
            .filter(_.tripClassifier == CAR)
            .head
            .legs
            .view
            .filter(_.beamLeg.mode == CAR)
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

      val (newVehicle, newVehicleToken) = if (leg1.beamLeg.mode == CAR || currentBeamVehicle.id == body.id) {
        (data.currentVehicle, currentBeamVehicle)
      } else {
        currentBeamVehicle.unsetDriver()
        eventsManager.processEvent(
          new PersonLeavesVehicleEvent(tick, id, data.currentVehicle.head)
        )
        (data.currentVehicle.drop(1), body)
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

  def calculateScore(
    walkingDistance: Double,
    cost: Double,
    energyCharge: Double,
    valueOfTime: Double
  ): Double = -cost - energyCharge

  /**
    * Calculates the duration of the refuel session, the provided energy and throws corresponding events
    *
    * @param currentTick
    * @param vehicle
    */
  def handleEndCharging(currentTick: Int, vehicle: BeamVehicle) = {

    val (chargingDuration, energyInJoules) =
      vehicle.refuelingSessionDurationAndEnergyInJoules(Some(currentTick - vehicle.getChargerConnectedTick()))

    log.debug("Ending refuel session for {} in tick {}. Provided {} J.", vehicle.id, currentTick, energyInJoules)
    vehicle.addFuel(energyInJoules)
    eventsManager.processEvent(
      new RefuelSessionEvent(
        currentTick,
        vehicle.stall.get.copy(locationUTM = beamServices.geo.utm2Wgs(vehicle.stall.get.locationUTM)),
        energyInJoules,
        chargingDuration,
        vehicle.id
      )
    )

    vehicle.disconnectFromChargingPoint()
    eventsManager.processEvent(
      new ChargingPlugOutEvent(
        currentTick,
        vehicle.stall.get
          .copy(locationUTM = beamServices.geo.utm2Wgs(vehicle.stall.get.locationUTM)),
        vehicle.id,
        vehicle.primaryFuelLevelInJoules,
        Some(vehicle.secondaryFuelLevelInJoules)
      )
    )
    log.debug(
      "Vehicle {} disconnected from charger @ stall {}",
      vehicle.id,
      vehicle.stall.get
    )

  }

}
