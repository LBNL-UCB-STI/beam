package beam.agentsim.agents.vehicles

import akka.actor.{ActorRef, Props}
import beam.agentsim.agents.BeamAgent.BeamAgentData
import beam.agentsim.agents.{BeamAgent, PersonAgent}
import beam.sim.{BeamServices, HasServices}
import org.matsim.api.core.v01.Id
import org.matsim.utils.objectattributes.attributable.Attributes
import org.matsim.vehicles._



class CarVehicle(val beamServices: BeamServices,
                 val vehicleId: Id[Vehicle],
                 val data: CarVehicleData,
                 var powerTrain: Powertrain,
                 val initialMatsimVehicle: Vehicle,
                 val initialMatsimAttributes: Attributes) extends BeamVehicle with HasServices {

  override val id: Id[Vehicle] = vehicleId
  override val vehicleTypeName: String = "CarVehicle"
  override val vehicleClassName: String = "CarVehicle"
  override def getType: VehicleType = carVehicleType
  override def matSimVehicle: Vehicle = initialMatsimVehicle
  override def attributes: Attributes = initialMatsimAttributes
  override def getId: Id[Vehicle] = id

  private lazy val carVehicleType = initVehicleType()
  val dim: CarDimension = CarDimension(1800, 4.8, 2)
  private def initVehicleType() = {
    val t  = VehicleUtils.getFactory.createVehicleType(Id.create("CarVehicle", classOf[VehicleType]))
    val cap = VehicleUtils.getFactory.createVehicleCapacity()
    cap.setSeats(5)
    cap.setStandingRoom(0)
    t.setCapacity(cap)
    t
  }
}

/**
  * @param weight in kilo
  * @param length in m
  * @param width in m
  */
case class CarDimension(weight: Double, length: Double, width: Double ) extends Dimension

case class CarVehicleData() extends BeamAgentData

object CarVehicle extends BeamVehicleObject{
  //TODO make HumanDimension come from somewhere

  // This props has it all
  def props(beamServices: BeamServices, vehicleId: Id[Vehicle], data: CarVehicleData, powerTrain: Powertrain,
             initialMatsimVehicle: Vehicle, initialMatsimAttributes: Attributes) = {
    Props(classOf[CarVehicle], beamServices, vehicleId, data, powerTrain, initialMatsimVehicle, initialMatsimAttributes)
  }

  // This props follows spec of BeamVehicle
  override def props(beamServices: BeamServices, vehicleId: Id[Vehicle], matSimVehicle: Vehicle, powertrain: Powertrain): Props = {
    val personId = Id.create("EMPTY",classOf[PersonAgent])
    props(beamServices, vehicleId, CarVehicleData(), powertrain, matSimVehicle, new Attributes())
  }

  // This props is specifically for vehicle creation during initialization
  def props(beamServices: BeamServices, matSimVehicle: Vehicle, powertrain: Powertrain): Props = {
    props(beamServices, matSimVehicle.getId, CarVehicleData(), powertrain, matSimVehicle,  new Attributes())
  }

}