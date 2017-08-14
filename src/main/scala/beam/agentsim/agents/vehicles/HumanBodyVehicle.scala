package beam.agentsim.agents.vehicles

import akka.actor.{ActorRef, Props}
import beam.agentsim.agents.{BeamAgent, PersonAgent}
import beam.sim.{BeamServices, HasServices}
import org.matsim.api.core.v01.Id
import org.matsim.vehicles._



class HumanBodyVehicle(val vehicleId: Id[Vehicle], val personId: Id[PersonAgent], val beamServices: BeamServices, val data: HumanBodyVehicleData,
                       var powerTrain: Powertrain, var driver: Option[ActorRef] = None) extends BeamVehicle with HasServices {

  override val id: Id[Vehicle] = vehicleId

}

/**
  *
  * @param weight in kilo
  * @param height in kilo
  */
case class HumanDimension(weight: Double, height: Double) extends Dimension

case class HumanBodyVehicleData(personId: Id[PersonAgent], dim: HumanDimension) extends VehicleData {
  private lazy val humanBodyVehicleType = initVehicleType()

  private def initVehicleType() = {
    val t  = VehicleUtils.getFactory.createVehicleType(Id.create("HumanBodyVehicle", classOf[VehicleType]))
    val cap = VehicleUtils.getFactory.createVehicleCapacity()
    cap.setSeats(1)
    cap.setStandingRoom(1)
    t.setCapacity(cap)
    t
  }

  override def vehicleTypeName: String = "HumanBodyVehicle"

  override def vehicleClassName: String = null

  override def getType: VehicleType = humanBodyVehicleType

  override def getId: Id[Vehicle] = Id.create(personId.toString, classOf[Vehicle])
}

object HumanBodyVehicle {
  //TODO make HumanDimension come from somewhere
  def props(services: BeamServices, personId: Id[PersonAgent]) = Props(classOf[HumanBodyVehicle],services,
    HumanBodyVehicleData(personId, HumanDimension(1.7, 60.0)), new Trajectory(), None)
}