package beam.agentsim.agents.vehicles

import akka.actor.{ActorRef, Props}
import beam.agentsim.agents.BeamAgent.BeamAgentData
import beam.agentsim.agents.{BeamAgent, PersonAgent}
import beam.sim.{BeamServices, HasServices}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.households.Household
import org.matsim.utils.objectattributes.attributable.Attributes
import org.matsim.vehicles._



class HumanBodyVehicle(val beamServices: BeamServices,
                       val vehicleId: Id[Vehicle],
                       val personId: Id[PersonAgent],
                       val data: HumanBodyVehicleData,
                       var powerTrain: Powertrain,
                       val initialMatsimVehicle: Vehicle,
                       val initialMatsimAttributes: Attributes) extends BeamVehicle with HasServices {

  override val id: Id[Vehicle] = vehicleId
  override val vehicleTypeName: String = "HumanBodyVehicle"
  override val vehicleClassName: String = "HumanBodyVehicle"
  override def getType: VehicleType = humanBodyVehicleType
  override def matSimVehicle: Vehicle = initialMatsimVehicle
  override def attributes: Attributes = initialMatsimAttributes
  override def getId: Id[Vehicle] = id

  private lazy val humanBodyVehicleType = initVehicleType()
  val dim: HumanDimension = HumanDimension(1.7, 60.0)
  private def initVehicleType() = {
    val t  = VehicleUtils.getFactory.createVehicleType(Id.create("HumanBodyVehicle", classOf[VehicleType]))
    val cap = VehicleUtils.getFactory.createVehicleCapacity()
    cap.setSeats(1)
    cap.setStandingRoom(0)
    t.setCapacity(cap)
    t
  }
}

/**
  * @param weight in kilo
  * @param height in kilo
  */
case class HumanDimension(weight: Double, height: Double) extends Dimension

case class HumanBodyVehicleData() extends BeamAgentData

object HumanBodyVehicle extends BeamVehicleObject{
  //TODO make HumanDimension come from somewhere

  // This props has it all
  def props(beamServices: BeamServices, vehicleId: Id[Vehicle], personId: Id[PersonAgent], data: HumanBodyVehicleData, powerTrain: Powertrain,
            initialMatsimVehicle: Vehicle, initialMatsimAttributes: Attributes) = {
    Props(classOf[HumanBodyVehicle], beamServices, vehicleId, personId, data, powerTrain, initialMatsimVehicle, initialMatsimAttributes)
  }

  // This props follows spec of BeamVehicle
  override def props(beamServices: BeamServices, vehicleId: Id[Vehicle], matSimVehicle: Vehicle, powertrain: Powertrain): Props = {
    val personId = Id.create("EMPTY",classOf[PersonAgent])
    props(beamServices, vehicleId, personId, HumanBodyVehicleData(), powertrain, matSimVehicle, new Attributes())
  }

  // This props is specifically for vehicle creation during initialization
  def props(beamServices: BeamServices, matSimVehicle: Vehicle, personId: Id[PersonAgent], powertrain: Powertrain): Props = {
    props(beamServices, matSimVehicle.getId, personId, HumanBodyVehicleData(), powertrain, matSimVehicle,  new Attributes())
  }

  def PowertrainForHumanBody(): Powertrain = Powertrain.PowertrainFromMilesPerGallon(360) // https://en.wikipedia.org/wiki/Energy_efficiency_in_transport#Walking

  def createId(personId: Id[Person]) : Id[Vehicle] = {
    Id.create("body-" + personId.toString, classOf[Vehicle])
  }

  def isHumanBodyVehicle(beamVehicleId: Id[Vehicle]) = beamVehicleId.toString.toLowerCase.contains("body")
  val placeHolderBodyVehilceId: Id[Vehicle] = Id.create("body",classOf[Vehicle])
}