package beam.agentsim.agents.parking

import java.util.concurrent.TimeUnit

import akka.pattern.{ask, pipe}
import akka.util.Timeout
import beam.agentsim.Resource.CheckInResource
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.PersonAgent.PersonData
import beam.agentsim.agents._
import beam.agentsim.agents.modalBehaviors.DrivesVehicle
import beam.agentsim.agents.modalBehaviors.DrivesVehicle.StartLegTrigger
import beam.agentsim.agents.parking.ChoosesParking.{ChoosingParkingSpot, ReleasingParkingSpot}
import beam.agentsim.agents.vehicles.PassengerSchedule
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.{LeavingParkingEvent, ParkEvent, SpaceTime}
import beam.agentsim.infrastructure.ParkingManager.{ParkingInquiry, ParkingInquiryResponse}
import beam.agentsim.infrastructure.ParkingStall.NoNeed
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.TriggerWithId
import beam.router.BeamRouter.{RoutingRequest, RoutingResponse}
import beam.router.Modes.BeamMode.{CAR, WALK}
import beam.router.RoutingModel
import beam.router.RoutingModel.{BeamLeg, DiscreteTime, EmbodiedBeamLeg, EmbodiedBeamTrip}
import tscfg.model.DURATION

import scala.concurrent.Future
import scala.concurrent.duration.Duration

//import scala.collection.JavaConverters._
//import scala.concurrent.duration._


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

      //personData.currentVehicle.head

      val firstLeg = personData.restOfCurrentTrip.head
      val lastLeg = personData.restOfCurrentTrip.takeWhile(_.beamVehicleId == firstLeg.beamVehicleId).last

      //TODO source value of time from appropriate place
      parkingManager ! ParkingInquiry(id, beamServices.geo.wgs2Utm(lastLeg.beamLeg.travelPath.startPoint.loc),
        beamServices.geo.wgs2Utm(lastLeg.beamLeg.travelPath.endPoint.loc), nextActivity(personData).right.get.getType,
        17.0, NoNeed, lastLeg.beamLeg.endTime, nextActivity(personData).right.get.getEndTime - lastLeg.beamLeg.endTime.toDouble)
  }
  when(ReleasingParkingSpot, stateTimeout = Duration.Zero) {
    case Event(TriggerWithId(StartLegTrigger(_, _), _), data) =>
      stash()
      stay using data
    case Event(StateTimeout, data@BasePersonData(_, _,_,_,_,_,_,_,_)) =>
      val (tick, _) = releaseTickAndTriggerId()
      val currVeh = data.currentVehicle.head
      val veh = beamServices
        .vehicles(data.currentVehicle.head)

      veh.stall.foreach{ stall =>
        parkingManager ! CheckInResource(beamServices.vehicles(data.currentVehicle.head).stall.get.id,None)
        beamServices.vehicles(data.currentVehicle.head).unsetParkingStall()
//        val tick: Double = _currentTick.getOrElse(0)
        val nextLeg = data.passengerSchedule.schedule.head._1
        val distance = beamServices.geo.distInMeters(stall.location, nextLeg.travelPath.endPoint.loc) //nextLeg.travelPath.endPoint.loc
        val cost = stall.cost
        val energyCharge: Double = 0.0 //TODO
        val valueOfTime: Double = getValueOfTime
        val score = calculateScore(distance, cost, energyCharge, valueOfTime)
        eventsManager.processEvent(new LeavingParkingEvent(tick, stall, score, veh.id))
      }
      goto(WaitingToDrive) using data

    case Event(StateTimeout, data) =>
      parkingManager ! CheckInResource(beamServices.vehicles(data.currentVehicle.head).stall.get.id,None)
      beamServices.vehicles(data.currentVehicle.head).unsetParkingStall()
      releaseTickAndTriggerId()
      goto(WaitingToDrive) using data
  }
  when(ChoosingParkingSpot) {
    case Event(ParkingInquiryResponse(stall), data) =>

      val distanceThresholdToIgnoreWalking = beamServices.beamConfig.beam.agentsim.thresholdForWalkingInMeters
      val nextLeg = data.passengerSchedule.schedule.keys.drop(data.currentLegPassengerScheduleIndex).head
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
        val (_, triggerId) = releaseTickAndTriggerId()
        scheduler ! CompletionNotice(triggerId, Vector(ScheduleTrigger(StartLegTrigger(nextLeg.startTime, nextLeg), self)))

//        val vehId = data.currentVehicle.head
//        eventsManager.processEvent(new ParkEvent(tick, stall, distance, vehId)) // nextLeg.endTime -> to fix repeated path traversal

        goto(WaitingToDrive) using data
      } else {
        // Else the stall requires a diversion in travel, calc the new routes (in-vehicle to the stall and walking to the destination)
        // In our routing requests we set mustParkAtEnd to false to prevent the router from splitting our routes for us
        import context.dispatcher
        val currentPoint = nextLeg.travelPath.startPoint
        val finalPoint = nextLeg.travelPath.endPoint

        // get route from customer to stall, add body for backup in case car route fails
        val carStreetVeh = StreetVehicle(data.currentVehicle.head, currentPoint, CAR, true)
        val bodyStreetVeh = StreetVehicle(data.currentVehicle.last, currentPoint,WALK,true)
        val futureVehicle2StallResponse = router ? RoutingRequest(currentPoint.loc, beamServices.geo.utm2Wgs(stall.location),
          DiscreteTime(currentPoint.time.toInt), Vector(), Vector(carStreetVeh,bodyStreetVeh), true, false)

        // get walk route from stall to destination, note we give a dummy start time and update later based on drive time to stall
        val futureStall2DestinationResponse = router ? RoutingRequest(beamServices.geo.utm2Wgs(stall.location), finalPoint.loc,
          DiscreteTime(currentPoint.time.toInt), Vector(), Vector(StreetVehicle(data.currentVehicle.last, SpaceTime(stall.location, currentPoint.time), WALK, true)), true, false)

        val responses = for {
          vehicle2StallResponse <- futureVehicle2StallResponse.mapTo[RoutingResponse]
          stall2DestinationResponse <- futureStall2DestinationResponse.mapTo[RoutingResponse]
        } yield (vehicle2StallResponse, stall2DestinationResponse)

        responses pipeTo self

        stay using data
      }
    case Event(responses: (RoutingResponse, RoutingResponse),data@BasePersonData(_,_,_,_,_,_,_,_,_)) =>
      val (tick, triggerId) = releaseTickAndTriggerId()
      val nextLeg = data.passengerSchedule.schedule.keys.drop(data.currentLegPassengerScheduleIndex).head

      // If no car leg returned, then the person walks to the parking spot and we force an early exit
      // from the vehicle below.
      val leg1 = if(responses._1.itineraries.filter(_.tripClassifier == CAR).isEmpty){
        logDebug(s"no CAR leg returned by router, walking car there instead")
        responses._1.itineraries.filter(_.tripClassifier == WALK).head.legs.head
      }else{
        responses._1.itineraries.filter(_.tripClassifier == CAR).head.legs.head
      }
      // Update start time of the second leg
      var leg2 = responses._2.itineraries.head.legs.head
      leg2 = leg2.copy(beamLeg = leg2.beamLeg.updateStartTime(leg1.beamLeg.endTime))

      // update person data with new legs
      val firstLeg = data.restOfCurrentTrip.head
      var legsToDrop = data.restOfCurrentTrip.takeWhile(_.beamVehicleId == firstLeg.beamVehicleId)
      if (legsToDrop.size == data.restOfCurrentTrip.size - 1) legsToDrop = data.restOfCurrentTrip
      val newRestOfTrip = leg1 +: (leg2 +: data.restOfCurrentTrip.filter { leg => !legsToDrop.exists(dropLeg => dropLeg.beamLeg == leg.beamLeg) }).toVector
      val newCurrentTripLegs = data.currentTrip.get.legs.takeWhile(_.beamLeg != nextLeg) ++ newRestOfTrip
      val newCurrentTrip = data.currentTrip.get.copy(newCurrentTripLegs)
      val newPassengerSchedule = PassengerSchedule().addLegs(Vector(newRestOfTrip.head.beamLeg))

      //        val newPersonData = data.restOfCurrentTrip.copy()

      scheduler ! CompletionNotice(triggerId, Vector(ScheduleTrigger(StartLegTrigger(newRestOfTrip.head.beamLeg.startTime, newRestOfTrip.head.beamLeg), self)))

      val currVehicle = beamServices.vehicles(data.currentVehicle.head)
      currVehicle.stall
        .foreach{ stall =>
          val distance = beamServices.geo.distInMeters(stall.location, nextLeg.travelPath.endPoint.loc)
          eventsManager.processEvent(new ParkEvent(tick, stall, distance, data.currentVehicle.head))
        }

      val newVehicle = if(leg1.beamLeg.mode==CAR){
        data.currentVehicle
      }else{
        currVehicle.unsetDriver()
        data.currentVehicle.drop(1)
      }

      goto(WaitingToDrive) using data.copy(currentTrip = Some(EmbodiedBeamTrip(newCurrentTripLegs)), currentVehicle = newVehicle,
        restOfCurrentTrip = newRestOfTrip.toList,passengerSchedule = newPassengerSchedule,currentLegPassengerScheduleIndex = 0)
  }

  def calculateScore(walkingDistance: Double, cost: Double, energyCharge: Double, valueOfTime: Double): Double = 0.0 //TODO
}

