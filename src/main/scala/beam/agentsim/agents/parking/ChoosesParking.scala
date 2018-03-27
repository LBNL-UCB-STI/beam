package beam.agentsim.agents.parking

import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents._
import beam.agentsim.agents.modalBehaviors.DrivesVehicle.StartLegTrigger
import beam.agentsim.agents.parking.ChoosesParking.{ChoosesParkingData, ChoosingParkingSpot}
import beam.agentsim.agents.vehicles.PassengerSchedule
import beam.agentsim.infrastructure.ParkingManager.{ParkingInquiry, ParkingInquiryResponse}
import beam.agentsim.infrastructure.ParkingStall.NoNeed
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.router.RoutingModel.BeamLeg

import scala.collection.JavaConverters._
import scala.concurrent.duration._


/**
  * BEAM
  */
object ChoosesParking {
  case class ChoosesParkingData(personData: BasePersonData) extends PersonData {
    override def currentVehicle: VehicleStack = personData.currentVehicle
    override def passengerSchedule: PassengerSchedule = personData.passengerSchedule
    override def withPassengerSchedule(newPassengerSchedule: PassengerSchedule): DrivingData = copy(personData = personData.copy(passengerSchedule = newPassengerSchedule))
    override def hasParkingBehaviors: Boolean = true
  }
  case object ChoosingParkingSpot extends BeamAgentState
}
trait ChoosesParking {
  this: PersonAgent => // Self type restricts this trait to only mix into a PersonAgent

  onTransition {
    case Driving -> ChoosingParkingSpot =>
      val personData = stateData.asInstanceOf[BasePersonData]
      val nextBeamLeg: BeamLeg = personData.restOfCurrentTrip.head.beamLeg

      //TODO source value of time from appropriate place
      parkingManager ! ParkingInquiry(id, beamServices.geo.wgs2Utm(nextBeamLeg.travelPath.startPoint.loc),
        beamServices.geo.wgs2Utm(nextBeamLeg.travelPath.endPoint.loc), nextActivity(personData).right.get.getType,
        17.0, NoNeed, nextBeamLeg.endTime, nextActivity(personData).right.get.getEndTime - nextBeamLeg.endTime.toDouble)
  }
  when(ChoosingParkingSpot) {
    case Event(ParkingInquiryResponse(stall), data@ChoosesParkingData(_)) =>
      val (tick, triggerId) = releaseTickAndTriggerId()

      stall

      val nextLeg = data.passengerSchedule.schedule.head._1
      goto(WaitingToDrive) using data.personData replying CompletionNotice(triggerId, Vector(ScheduleTrigger(StartLegTrigger(nextLeg.startTime, nextLeg), self)))
  }

}

