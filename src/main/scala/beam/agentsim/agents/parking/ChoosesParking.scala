package beam.agentsim.agents.parking

import scala.concurrent.duration.Duration

import akka.actor.Stash
import akka.pattern.{ask, pipe}
import beam.agentsim.Resource.CheckInResource
import beam.agentsim.agents._
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.modalBehaviors.DrivesVehicle.StartLegTrigger
import beam.agentsim.agents.parking.ChoosesParking.{ChoosingParkingSpot, ReleasingParkingSpot}
import beam.agentsim.agents.vehicles.PassengerSchedule
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.{LeavingParkingEvent, ParkEvent, SpaceTime}
import beam.agentsim.infrastructure.ParkingManager.{ParkingInquiry, ParkingInquiryResponse}
import beam.agentsim.infrastructure.ParkingStall.NoNeed
import beam.agentsim.scheduler.TriggerWithId
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.router.BeamRouter.{RoutingRequest, RoutingResponse}
import beam.router.Modes.BeamMode.{CAR, WALK}
import beam.router.RoutingModel.{DiscreteTime, EmbodiedBeamTrip}
import beam.sim.HasServices

//import scala.collection.JavaConverters._
//import scala.concurrent.duration._


/**
  * BEAM
  */
object ChoosesParking {
  case object ChoosingParkingSpot extends BeamAgentState
  case object ReleasingParkingSpot extends BeamAgentState
}
trait ChoosesParking[T <: DrivingData] extends BeamAgent[T] with HasServices with Stash  {
  this: PersonAgent => // Self type restricts this trait to only mix into a PersonAgent

  onTransition {
    case Driving -> ChoosingParkingSpot =>
      val drivingData = stateData.asInstanceOf[LiterallyDrivingData]

      //drivingData.currentVehicle.head

      val firstLeg = drivingData.passengerSchedule.schedule.firstKey
      val lastLeg = drivingData.passengerSchedule.schedule.lastKey

      //TODO source value of time from appropriate place
      parkingManager ! ParkingInquiry(id, beamServices.geo.wgs2Utm(lastLeg.travelPath.startPoint.loc),
        beamServices.geo.wgs2Utm(lastLeg.travelPath.endPoint.loc), nextActivity(drivingData).right.get.getType,
        17.0, NoNeed, lastLeg.endTime, nextActivity(drivingData).right.get.getEndTime - lastLeg.endTime.toDouble)
  }
  when(ReleasingParkingSpot, stateTimeout = Duration.Zero) {
    case Event(TriggerWithId(StartLegTrigger(tick, newLeg), triggerId), data) =>

      val veh = beamServices
        .vehicles(
          data.currentVehicle.head)

      //
      veh.stall.foreach{ stall =>
//        val nextLeg = data.passengerSchedule.schedule.head._1
        val distance = beamServices.geo.distInMeters(stall.location, newLeg.travelPath.endPoint.loc) //nextLeg.travelPath.endPoint.loc
        val cost = stall.cost
        val energyCharge: Double = 0.0 //TODO
        val valueOfTime: Double = getValueOfTime
        val score = calculateScore(distance, cost, energyCharge, valueOfTime)
        eventsManager.processEvent(new LeavingParkingEvent(tick, stall, score, veh.id))
      }

      stash()
      stay using data
    case Event(StateTimeout, data @ ReleasingParkingSpot(_)) =>
      parkingManager ! CheckInResource(beamServices.vehicles(data.currentVehicle.head).stall.get.id,None)
      beamServices.vehicles(data.currentVehicle.head).unsetParkingStall()
      goto(WaitingToDrive) using data.personData
  }
  when(ChoosingParkingSpot) {
    case Event(ParkingInquiryResponse(stall), data@ChoosesParkingData(_)) =>

      val distanceThresholdToIgnoreWalking = beamServices.beamConfig.beam.agentsim.thresholdForWalkingInMeters
      val nextLeg = data.passengerSchedule.schedule.head._1
      beamServices.vehicles(data.currentVehicle.head).useParkingStall(stall)

      data.currentVehicle.head

      //Veh id
      //distance to dest
      //parking Id
      //cost
      //location

      val distance = beamServices.geo.distInMeters(stall.location, nextLeg.travelPath.endPoint.loc)
      // If the stall is co-located with our destination... then continue on but add the stall to PersonData
      if (distance <= distanceThresholdToIgnoreWalking) {
        val (tick, triggerId) = releaseTickAndTriggerId()
        scheduler ! CompletionNotice(triggerId, Vector(ScheduleTrigger(StartLegTrigger(nextLeg.startTime, nextLeg), self)))
        //data for event
        val vehId = data.currentVehicle.head
        eventsManager.processEvent(new ParkEvent(tick, stall, distance, vehId))

        goto(WaitingToDrive) using data
      } else {
        // Else the stall requires a diversion in travel, calc the new routes (in-vehicle to the stall and walking to the destination)
        // In our routing requests we set mustParkAtEnd to false to prevent the router from splitting our routes for us
        import context.dispatcher
        val currentPoint = nextLeg.travelPath.startPoint
        val finalPoint = nextLeg.travelPath.endPoint

        // get route from customer to stall
        val futureVehicle2StallResponse = router ? RoutingRequest(currentPoint.loc, beamServices.geo.utm2Wgs(stall.location),
          DiscreteTime(currentPoint.time.toInt), Vector(), Vector(StreetVehicle(data.personData.currentVehicle.head, currentPoint, CAR, true)), true, false)

        // get walk route from stall to destination, note we give a dummy start time and update later based on drive time to stall
        val futureStall2DestinationResponse = router ? RoutingRequest(beamServices.geo.utm2Wgs(stall.location), finalPoint.loc,
          DiscreteTime(currentPoint.time.toInt), Vector(), Vector(StreetVehicle(data.personData.currentVehicle.last, SpaceTime(stall.location, currentPoint.time), WALK, true)), true, false)

        val responses = for {
          vehicle2StallResponse <- futureVehicle2StallResponse.mapTo[RoutingResponse]
          stall2DestinationResponse <- futureStall2DestinationResponse.mapTo[RoutingResponse]
        } yield (vehicle2StallResponse, stall2DestinationResponse)

        responses pipeTo self

        stay using data
      }
    case Event(responses: (RoutingResponse, RoutingResponse),data@ChoosesParkingData(_)) =>
      val (tick, triggerId) = releaseTickAndTriggerId()
      val nextLeg = data.passengerSchedule.schedule.head._1


      if(responses._1.itineraries.isEmpty){
        val i = 0
      }
      // Update start time the walk leg
      val leg1 = responses._1.itineraries.head.legs.head
      var leg2 = responses._2.itineraries.head.legs.head
      leg2 = leg2.copy(beamLeg = leg2.beamLeg.updateStartTime(leg1.beamLeg.endTime))

      // update person data with new legs
      val firstLeg = data.personData.restOfCurrentTrip.head
      var legsToDrop = data.personData.restOfCurrentTrip.takeWhile(_.beamVehicleId == firstLeg.beamVehicleId)
      if (legsToDrop.size == data.personData.restOfCurrentTrip.size - 1) legsToDrop = data.personData.restOfCurrentTrip
      val newRestOfTrip = leg1 +: (leg2 +: data.personData.restOfCurrentTrip.filter { leg => !legsToDrop.exists(dropLeg => dropLeg.beamLeg == leg.beamLeg) }).toVector
      val newCurrentTripLegs = data.personData.currentTrip.get.legs.takeWhile(_.beamLeg != nextLeg) ++ newRestOfTrip
      val newCurrentTrip = data.personData.currentTrip.get.copy(newCurrentTripLegs)
      val newPassengerSchedule = PassengerSchedule().addLegs(Vector(newRestOfTrip.head.beamLeg))

      //        val newPersonData = data.personData.restOfCurrentTrip.copy()

      scheduler ! CompletionNotice(triggerId, Vector(ScheduleTrigger(StartLegTrigger(newRestOfTrip.head.beamLeg.startTime, newRestOfTrip.head.beamLeg), self)))

      val currVehicle = beamServices
        .vehicles(
          data.currentVehicle.head)
      currVehicle.stall
        .foreach{ stall =>
          val distance = beamServices.geo.distInMeters(stall.location, nextLeg.travelPath.endPoint.loc)
          eventsManager.processEvent(new ParkEvent(tick, stall, distance, data.currentVehicle.head))
        }

      goto(WaitingToDrive) using data.personData.copy(currentTrip = Some(EmbodiedBeamTrip(newCurrentTripLegs)),
        restOfCurrentTrip = newRestOfTrip.toList).withPassengerSchedule(newPassengerSchedule).asInstanceOf[PersonData]
  }

  def calculateScore(walkingDistance: Double, cost: Double, energyCharge: Double, valueOfTime: Double): Double = 0.0 //TODO
}

