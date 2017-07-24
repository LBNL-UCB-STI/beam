package beam.agentsim.agents.vehicles

import akka.actor.{Actor, ActorRef}
import beam.agentsim.agents.PersonAgent.PersonData
import beam.agentsim.agents.{BeamAgent, PersonAgent}
import org.matsim.api.core.v01.Id
import org.matsim.vehicles._



class HumanBodyVehicle(val personId: Id[PersonAgent], val data: HumanBodyVehicleData,
                       val trajectory: Trajectory, val powerTrain: Powertrain,
                       var driver: Option[ActorRef] = None) extends BeamVehicle with Actor {
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

case class HumanBodyVehicleData(personId: Id[PersonAgent], personData: PersonData, dim: HumanDimension) extends VehicleData {
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