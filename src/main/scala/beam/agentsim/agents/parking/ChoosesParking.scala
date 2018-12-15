package beam.agentsim.agents.parking

import akka.actor.FSM.Failure
import akka.pattern.{ask, pipe}
import beam.agentsim.Resource.CheckInResource
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents._
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.StartLegTrigger
import beam.agentsim.agents.parking.ChoosesParking.{ChoosingParkingSpot, ReleasingParkingSpot}
import beam.agentsim.agents.vehicles.PassengerSchedule
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.{LeavingParkingEvent, SpaceTime}
import beam.agentsim.infrastructure.ParkingManager.{ParkingInquiry, ParkingInquiryResponse}
import beam.agentsim.infrastructure.ParkingStall.NoNeed
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.BeamRouter.{RoutingRequest, RoutingResponse}
import beam.router.Modes.BeamMode.{CAR, WALK}
import beam.router.model.EmbodiedBeamTrip
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent

import scala.concurrent.duration.Duration

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

      parkingManager ! ParkingInquiry(
        id,
        beamServices.geo.wgs2Utm(lastLeg.beamLeg.travelPath.startPoint.loc),
        beamServices.geo.wgs2Utm(lastLeg.beamLeg.travelPath.endPoint.loc),
        nextActivity(personData).get.getType,
        attributes.valueOfTime,
        NoNeed,
        lastLeg.beamLeg.endTime,
        nextActivity(personData).get.getEndTime - lastLeg.beamLeg.endTime.toDouble
      )
  }
  when(ReleasingParkingSpot, stateTimeout = Duration.Zero) {
    case Event(TriggerWithId(StartLegTrigger(_, _), _), data) =>
      stash()
      stay using data
    case Event(StateTimeout, data: BasePersonData) =>
      if (data.currentVehicle.isEmpty) {
        stop(Failure(s"Cannot release parking spot when data.currentVehicle is empty for person $id"))
      } else {
        val (tick, _) = releaseTickAndTriggerId()
        val veh = data.currentTourPersonalVehicle.get
        assert(veh.id == data.currentVehicle.head)

        veh.stall.foreach { stall =>
          parkingManager ! CheckInResource(data.currentTourPersonalVehicle.get.stall.get.id, None)
          //        val tick: Double = _currentTick.getOrElse(0)
          val nextLeg = data.passengerSchedule.schedule.head._1
          val distance = beamServices.geo.distInMeters(
            stall.location,
            nextLeg.travelPath.endPoint.loc
          ) //nextLeg.travelPath.endPoint.loc
          val cost = stall.cost
          val energyCharge: Double = 0.0 //TODO
          val timeCost: Double = scaleTimeByValueOfTime(0.0) // TODO: CJRS... let's discuss how to fix this - SAF
          val score = calculateScore(distance, cost, energyCharge, timeCost)
          eventsManager.processEvent(new LeavingParkingEvent(tick, stall, score, id, veh.id))
        }
        veh.unsetParkingStall()
        goto(WaitingToDrive) using data
      }

    case Event(StateTimeout, data) =>
      parkingManager ! CheckInResource(
        data.currentVehicleToken.stall.get.id,
        None
      )
      data.currentVehicleToken.unsetParkingStall()
      releaseTickAndTriggerId()
      goto(WaitingToDrive) using data
  }
  when(ChoosingParkingSpot) {
    case Event(ParkingInquiryResponse(stall, _), data) =>
      val distanceThresholdToIgnoreWalking =
        beamServices.beamConfig.beam.agentsim.thresholdForWalkingInMeters
      val nextLeg =
        data.passengerSchedule.schedule.keys.drop(data.currentLegPassengerScheduleIndex).head
      data.currentVehicleToken.setReservedParkingStall(Some(stall))

      data.currentVehicle.head

      //Veh id
      //distance to dest
      //parking Id
      //cost
      //location

      val distance = beamServices.geo.distInMeters(stall.location, nextLeg.travelPath.endPoint.loc)
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
        // In our routing requests we set mustParkAtEnd to false to prevent the router from splitting our routes for us
        import context.dispatcher
        val currentPoint = nextLeg.travelPath.startPoint
        val finalPoint = nextLeg.travelPath.endPoint

        // get route from customer to stall, add body for backup in case car route fails
        val carStreetVeh =
          StreetVehicle(data.currentVehicleToken.id, data.currentVehicleToken.beamVehicleType.id, currentPoint, CAR, asDriver = true)
        val bodyStreetVeh =
          StreetVehicle(body.id, body.beamVehicleType.id, currentPoint, WALK, asDriver = true)
        val futureVehicle2StallResponse = router ? RoutingRequest(
          currentPoint.loc,
          beamServices.geo.utm2Wgs(stall.location),
          currentPoint.time,
          Vector(),
          Vector(carStreetVeh, bodyStreetVeh),
          Some(attributes)
        )

        // get walk route from stall to destination, note we give a dummy start time and update later based on drive time to stall
        val futureStall2DestinationResponse = router ? RoutingRequest(
          beamServices.geo.utm2Wgs(stall.location),
          finalPoint.loc,
          currentPoint.time,
          Vector(),
          Vector(
            StreetVehicle(
              body.id,
              body.beamVehicleType.id,
              SpaceTime(stall.location, currentPoint.time),
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

      // If no car leg returned, then the person walks to the parking spot and we force an early exit
      // from the vehicle below.
      val leg1 = if (!routingResponse1.itineraries.exists(_.tripClassifier == CAR)) {
        logDebug(s"no CAR leg returned by router, walking car there instead")
        routingResponse1.itineraries.filter(_.tripClassifier == WALK).head.legs.head
      } else {
        routingResponse1.itineraries.filter(_.tripClassifier == CAR).head.legs(1)
      }
      // Update start time of the second leg
      var leg2 = routingResponse2.itineraries.head.legs.head
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

      val (newVehicle, newVehicleToken) = if (leg1.beamLeg.mode == CAR || data.currentVehicleToken.id == body.id) {
        (data.currentVehicle, data.currentVehicleToken)
      } else {
        data.currentVehicleToken.unsetDriver()
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
        currentVehicle = newVehicle,
        currentVehicleToken = newVehicleToken
      )
  }

  def calculateScore(
    walkingDistance: Double,
    cost: Double,
    energyCharge: Double,
    valueOfTime: Double
  ): Double = -cost - energyCharge
}
