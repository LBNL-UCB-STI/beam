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
import beam.router.model.{BeamPath, EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.r5.R5RoutingWorker
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
    case Event(StateTimeout, data @ BasePersonData(_, _, _, _, _, _, _, _, _, _)) =>
      if (data.currentVehicle.isEmpty) {
        stop(Failure(s"Cannot release parking spot when data.currentVehicle is empty for person $id"))
      } else {
        val (tick, _) = releaseTickAndTriggerId()
        val veh = beamServices
          .vehicles(data.currentVehicle.head)

        veh.stall.foreach { stall =>
          parkingManager ! CheckInResource(
            beamServices.vehicles(data.currentVehicle.head).stall.get.id,
            None
          )
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
        beamServices.vehicles(data.currentVehicle.head).stall.get.id,
        None
      )
      beamServices.vehicles(data.currentVehicle.head).unsetParkingStall()
      releaseTickAndTriggerId()
      goto(WaitingToDrive) using data
  }
  when(ChoosingParkingSpot) {
    case Event(ParkingInquiryResponse(stall, _), data) =>
      val distanceThresholdToIgnoreWalking =
        beamServices.beamConfig.beam.agentsim.thresholdForWalkingInMeters
      val nextLeg =
        data.passengerSchedule.schedule.keys.drop(data.currentLegPassengerScheduleIndex).head
      beamServices.vehicles(data.currentVehicle.head).setReservedParkingStall(Some(stall))

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
          StreetVehicle(data.currentVehicle.head, currentPoint, CAR, asDriver = true)
        val bodyStreetVeh =
          StreetVehicle(data.currentVehicle.last, currentPoint, WALK, asDriver = true)
        val veh2StallRequest = RoutingRequest(
          currentPoint.loc,
          beamServices.geo.utm2Wgs(stall.location),
          currentPoint.time,
          Vector(),
          Vector(carStreetVeh, bodyStreetVeh),
          Some(attributes)
        )
        val futureVehicle2StallResponse = router ? veh2StallRequest

        // get walk route from stall to destination, note we give a dummy start time and update later based on drive time to stall
        val futureStall2DestinationResponse = router ? RoutingRequest(
          beamServices.geo.utm2Wgs(stall.location),
          finalPoint.loc,
          currentPoint.time,
          Vector(),
          Vector(
            StreetVehicle(
              data.currentVehicle.last,
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
        data @ BasePersonData(_, _, _, _, _, _, _, _, _, _)
        ) =>
      val (tick, triggerId) = releaseTickAndTriggerId()
      val nextLeg =
        data.passengerSchedule.schedule.keys.drop(data.currentLegPassengerScheduleIndex).head

      // If no car leg returned, then the person walks to the parking spot and we force an early exit
      // from the vehicle below.
      var (leg1, leg2) = if (!routingResponse1.itineraries.exists(_.tripClassifier == CAR)) {
        logDebug(s"no CAR leg returned by router, creating dummy car leg instead")
        val theWalkLeg = routingResponse1.itineraries.filter(_.tripClassifier == WALK).head.legs.head
        val theDrivePath = BeamPath(Vector(nextLeg.travelPath.linkIds.last),
          Vector(nextLeg.travelPath.linkTravelTime.last),
          None,
          nextLeg.travelPath.startPoint,
          nextLeg.travelPath.startPoint,
          0.0
        )
        (theWalkLeg.copy(
          unbecomeDriverOnCompletion = true,
          beamVehicleId = data.currentVehicle.head,
          beamLeg = theWalkLeg.beamLeg.copy(mode = CAR,travelPath = theDrivePath))
        ,
          EmbodiedBeamLeg(
          R5RoutingWorker.createBushwackingBeamLeg(nextLeg.startTime,
            nextLeg.travelPath.startPoint.loc,
            nextLeg.travelPath.endPoint.loc,
            beamServices),
          theWalkLeg.beamVehicleId,
          true,
          0.0,
          true
        ))
      } else {
        (routingResponse1.itineraries.filter(_.tripClassifier == CAR).head.legs(1),
          routingResponse2.itineraries.head.legs.head)
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

      val currVehicle = beamServices.vehicles(data.currentVehicle.head)

      val newVehicle = if (leg1.beamLeg.mode == CAR || currVehicle.id == bodyId) {
        data.currentVehicle
      } else {
        currVehicle.unsetDriver()
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

  def calculateScore(
    walkingDistance: Double,
    cost: Double,
    energyCharge: Double,
    valueOfTime: Double
  ): Double = -cost - energyCharge
}
