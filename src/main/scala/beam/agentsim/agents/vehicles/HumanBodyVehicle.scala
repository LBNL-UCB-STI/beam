package beam.agentsim.agents.vehicles

import akka.actor.{ActorRef, Props}
import beam.agentsim.agents.{BeamAgent, PersonAgent}
import beam.sim.{BeamServices, HasServices}
import org.matsim.api.core.v01.Id
import org.matsim.vehicles._



class HumanBodyVehicle(val personId: Id[PersonAgent], val beamServices: BeamServices, val data: HumanBodyVehicleData,
                       var trajectory: Trajectory, var powerTrain: Powertrain,
                       var driver: Option[ActorRef] = None) extends BeamVehicle with HasServices {

  //XXX: be careful with traversing,  possible recursion
  def passengers: List[ActorRef] = List(self)

  def carrier = None

  override def id: Id[_] = personId

  override protected def setDriver(newDriver: ActorRef): Unit = throw new UnsupportedOperationException

  override protected def pickupPassengers(newPassengers: List[ActorRef]): Unit = throw new UnsupportedOperationException

  override protected def dropOffPassengers(passengers: List[ActorRef]): List[ActorRef] = throw new UnsupportedOperationException
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