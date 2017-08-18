package beam.agentsim.agents.vehicles

import akka.actor.Props
import beam.agentsim.agents.BeamAgent.BeamAgentData
import beam.agentsim.agents.PersonAgent
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleObject, Dimension, Powertrain}
import beam.sim.{BeamServices, HasServices}
import org.matsim.api.core.v01.Id
import org.matsim.utils.objectattributes.attributable.Attributes
import org.matsim.vehicles.{Vehicle, VehicleType}

/**
  * BEAM
  */
class TransitVehicle(val beamServices: BeamServices,
  val vehicleId: Id[Vehicle],
  val data: TransitVehicleData,
  var powerTrain: Powertrain,
  val initialMatsimVehicle: Vehicle,
  val initialMatsimAttributes: Attributes) extends BeamVehicle with HasServices {

  override val id: Id[Vehicle] = vehicleId
  override val vehicleTypeName: String = "TransitVehicle"
  override val vehicleClassName: String = "TransitVehicle"
  override def getType: VehicleType = initialMatsimVehicle.getType
  override def matSimVehicle: Vehicle = initialMatsimVehicle
  override def attributes: Attributes = initialMatsimAttributes
  override def getId: Id[Vehicle] = id

  val dim: TransitVehicleDimension = TransitVehicleDimension(10e3, getType.getLength, getType.getWidth)
}

/**
  * @param weight in kilo
  * @param length in m
  * @param width in m
  */
case class TransitVehicleDimension(weight: Double, length: Double, width: Double ) extends Dimension

case class TransitVehicleData() extends BeamAgentData

object TransitVehicle extends BeamVehicleObject{
  // This props has it all
  def props(beamServices: BeamServices, vehicleId: Id[Vehicle], data: TransitVehicleData, powerTrain: Powertrain,
            initialMatsimVehicle: Vehicle, initialMatsimAttributes: Attributes) = {
    Props(classOf[TransitVehicle], beamServices, vehicleId, data, powerTrain, initialMatsimVehicle, initialMatsimAttributes)
  }

  // This props follows spec of BeamVehicle
  override def props(beamServices: BeamServices, vehicleId: Id[Vehicle], matSimVehicle: Vehicle, powertrain: Powertrain): Props = {
    props(beamServices, vehicleId, TransitVehicleData(), powertrain, matSimVehicle, new Attributes())
  }

  // This props is specifically for vehicle creation during initialization
  def props(beamServices: BeamServices, matSimVehicle: Vehicle, powertrain: Powertrain): Props = {
    props(beamServices, matSimVehicle.getId, TransitVehicleData(), powertrain, matSimVehicle,  new Attributes())
  }

}
