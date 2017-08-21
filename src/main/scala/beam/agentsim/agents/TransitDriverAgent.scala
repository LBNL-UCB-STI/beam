package beam.agentsim.agents

import akka.actor.Props
import beam.agentsim.agents.BeamAgent.{BeamAgentData, BeamAgentInfo, Uninitialized}
import beam.agentsim.agents.PersonAgent.Waiting
import beam.agentsim.agents.TransitDriverAgent.TransitDriverData
import beam.agentsim.agents.modalBehaviors.DrivesVehicle
import beam.agentsim.agents.modalBehaviors.DrivesVehicle.StartLegTrigger
import beam.agentsim.agents.vehicles.BeamVehicle.{BeamVehicleIdAndRef, BecomeDriver}
import beam.agentsim.agents.vehicles.PassengerSchedule
import beam.router.RoutingModel.EmbodiedBeamLeg
import beam.sim.{BeamServices, HasServices}
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

/**
  * BEAM
  */
object TransitDriverAgent {
  def props(services: BeamServices, transitDriverId: Id[TransitDriverAgent], vehicleIdAndRef: BeamVehicleIdAndRef, passengerSchedule: PassengerSchedule) = {
    Props(classOf[TransitDriverAgent], services, transitDriverId, TransitDriverData(vehicleIdAndRef, passengerSchedule))
  }
  case class TransitDriverData(vehicleUnderControl: BeamVehicleIdAndRef, passengerSchedule: PassengerSchedule) extends BeamAgentData

  def createAgentId(transitVehicle: Id[Vehicle]) = {
    Id.create("TransitDriverAgent-" + transitVehicle.toString, classOf[TransitDriverAgent])
  }
}

class TransitDriverAgent(val beamServices: BeamServices,
                         override val id: Id[TransitDriverAgent],

                         override val data: TransitDriverData) extends
  BeamAgent[TransitDriverData] with HasServices with DrivesVehicle[TransitDriverData] {
  override def logPrefix(): String = s"TransitDriverAgent:$id "

  chainedWhen(Uninitialized){
    case Event(InitializeTrigger(tick), info: BeamAgentInfo[TransitDriverData]) =>
      logDebug(s" $id has been initialized, going to Waiting state")
      data.vehicleUnderControl.ref ! BecomeDriver(tick, id, Option(data.passengerSchedule))
      val firstStop = data.passengerSchedule.getStartLed()
      val embodiedBeamLeg = EmbodiedBeamLeg(firstStop, data.vehicleUnderControl.id, asDriver = true, None, 0.0, unbecomeDriverOnCompletion =  false)
      //start Moving by scheduling startLeg trigger
      beamServices.schedulerRef  ! scheduleOne[StartLegTrigger](firstStop.startTime, self, embodiedBeamLeg)
      goto(PersonAgent.Waiting)
  }

//  chainedWhen(Idle) {
//  }

//  chainedWhen(Traveling) {
//  }

}
