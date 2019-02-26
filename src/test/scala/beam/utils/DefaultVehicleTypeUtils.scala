package beam.utils
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.FuelType.{Diesel, Food, FuelType, Gasoline}
import beam.agentsim.agents.vehicles.VehicleCategory._
import org.matsim.api.core.v01.Id

case class DefaultVehicleTypeUtils()

object DefaultVehicleTypeUtils {

  val defaultHumanBodyBeamVehicleType: BeamVehicleType = BeamVehicleType(
    Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
    0,
    0,
    0.5,
    Food,
    53,
    2.21e6,
    vehicleCategory = Body
  )

  val defaultBicycleBeamVehicleType: BeamVehicleType = BeamVehicleType(
    Id.create("BIKE_TYPE_DEFAULT", classOf[BeamVehicleType]),
    1,
    0,
    1.5,
    Food,
    defaultHumanBodyBeamVehicleType.primaryFuelConsumptionInJoulePerMeter / 5.0, // 5x more efficient than walking
    defaultHumanBodyBeamVehicleType.primaryFuelCapacityInJoule, // same capacity as human body
    vehicleCategory = Bike
  )

  val defaultCarBeamVehicleType: BeamVehicleType = BeamVehicleType(
    Id.create("CAR-TYPE-DEFAULT", classOf[BeamVehicleType]),
    4,
    0,
    4.5,
    Gasoline,
    3656.0,
    3655980000.0,
    vehicleCategory = Car
  )

  val defaultTransitBeamVehicleType: BeamVehicleType = BeamVehicleType(
    Id.create("TRANSIT-TYPE-DEFAULT", classOf[BeamVehicleType]),
    50,
    50,
    10,
    Diesel,
    25829.7,
    30000000000.0,
    vehicleCategory = MediumDutyPassenger
  )

  val defaultCAVBeamVehicleType = BeamVehicleType(
    Id.create("CAV-TYPE-DEFAULT", classOf[BeamVehicleType]),
    4,
    0,
    4.5,
    Gasoline,
    3656.0,
    3655980000.0,
    vehicleCategory = Car,
    automationLevel = 5
  )

}
