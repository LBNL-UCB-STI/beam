package beam.agentsim.agents.vehicles

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern._
import beam.agentsim.Resource
import beam.agentsim.agents.BeamAgent.BeamAgentData
import beam.agentsim.events.SpaceTime
import beam.agentsim.events.resources.vehicle.GetVehicleLocationEvent
import beam.sim.config.BeamConfig
import org.matsim.api.core.v01.Id
import org.matsim.utils.objectattributes.attributable.Attributes
import org.matsim.vehicles.{Vehicle, VehicleType}
import scala.concurrent.Future

/**
  * @author dserdiuk
  */

abstract class Dimension

object VehicleData {
  implicit def vehicle2vehicleData(vehicle: Vehicle): VehicleData = {
    val vdata = VehicleDataImpl(vehicle.getType.getDescription,
      vehicle.getClass.getName, vehicle, new Attributes())
    vdata
  }
}
trait VehicleData extends BeamAgentData with Vehicle {
  /**
    * It's pretty general name of type of vehicle.
    * It could be a model name of particular brand as well as vehicle class: sedan, truck, bus etc.
    * The key point this type need to be unique
    * @return
    */
  def vehicleTypeName: String

  /**
    * MATSim vehicle vehicle implementation class
    * @return
    */
  def vehicleClassName: String
}

object BeamVehicle {

  def energyPerUnitByType(vehicleTypeId: Id[VehicleType]): Double = {
    //TODO: add energy type registry
      0.0
  }

  def buildActorName(vehicleId: Id[Vehicle], iterationName: Option[String] = None) = {
    s"vehicle-${vehicleId.toString}" + iterationName.map(i => s"_iter-$i").getOrElse("")
  }
}

trait BeamVehicle extends Resource with Actor with ActorLogging {

  def driver: Option[ActorRef]

  /**
    * Other vehicle that carry this one. Like ferry or track may carry a car
    *
    * @return
    */
  def carrier: Option[ActorRef]

  def passengers: List[ActorRef]

  def vehicleData: VehicleData

  def trajectory: Trajectory

  def powerTrain: Powertrain

  def location(time: Double): Future[SpaceTime] = {
    carrier match {
      case Some(carrierVehicle) =>
        import beam.sim.BeamSim.askTimeout
        (carrierVehicle ? GetVehicleLocationEvent(time)).mapTo[SpaceTime].recover[SpaceTime] {
          case error: Throwable =>
          log.warning(s"Failed to get location of from carrier. ", error)
          trajectory.location(time)
        }(context.dispatcher)
      case None =>
        Future.successful(trajectory.location(time))
    }
  }
}

/**
  * VehicleDataImpl contains Attributes. These enumerations are defined to simplify extensibility of VehicleData
  */
object VehicleAttributes extends Enumeration {

  val capacity = Value("capacity")

  object Electric extends Enumeration {
    val electricEnergyConsumptionModelClassname = Value("electricEnergyConsumptionModelClassname")
    val batteryCapacityInKWh = Value("batteryCapacityInKWh")
    val maxDischargingPowerInKW = Value("maxDischargingPowerInKW")
    val maxLevel2ChargingPowerInKW = Value("maxLevel2ChargingPowerInKW")
    val maxLevel3ChargingPowerInKW = Value("maxLevel3ChargingPowerInKW")
    val targetCoefA = Value("targetCoefA")
    val targetCoefB = Value("targetCoefB")
    val targetCoefC = Value("targetCoefC")
  }

  object Gasoline extends Enumeration {
    val gasolineFuelConsumptionRateInJoulesPerMeter = Value("gasolineFuelConsumptionRateInJoulesPerMeter")
    val fuelEconomyInKwhPerMile = Value("fuelEconomyInKwhPerMile")
    val equivalentTestWeight = Value("equivalentTestWeight")
  }
}

case class VehicleDataImpl(vehicleTypeName: String, vehicleClassName: String,
                           matSimVehicle: Vehicle, attributes: Attributes) extends VehicleData {
  override def getType: VehicleType = matSimVehicle.getType

  override def getId: Id[Vehicle] = matSimVehicle.getId
}
