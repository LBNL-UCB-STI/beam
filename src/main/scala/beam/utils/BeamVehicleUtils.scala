package beam.utils

import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.{Vehicle, VehicleType, Vehicles}

import scala.collection.JavaConverters
import scala.collection.concurrent.TrieMap

object BeamVehicleUtils {

  def makeBicycle(id: Id[Vehicle]): BeamVehicle = {
    //FIXME: Every person gets a Bicycle (for now, 5/2018)

    val bvt = BeamVehicleType.defaultBicycleBeamVehicleType
    val beamVehicleId = BeamVehicle.createId(id, Some("bike"))
    val powertrain = Option(bvt.primaryFuelConsumptionInJoule)
      .map(new Powertrain(_))
      .getOrElse(Powertrain.PowertrainFromMilesPerGallon(Powertrain.AverageMilesPerGallon))
    new BeamVehicle(
      beamVehicleId,
      powertrain,
      None,
      bvt
    )
  }

//  def makeCar(
//               matsimVehicle: Vehicle,
//               vehicleRangeInMeters: Double,
//               refuelRateLimitInWatts: Option[Double]
//             ): BeamVehicle = {
//    val engineInformation = Option(matsimVehicle.getType.getEngineInformation)
//
//    val powerTrain = engineInformation match {
//      case Some(info) =>
//        Powertrain(info)
//      case None =>
//        Powertrain.PowertrainFromMilesPerGallon(Powertrain.AverageMilesPerGallon)
//    }
//
//    val fuelCapacityInJoules = vehicleRangeInMeters * powerTrain.estimateConsumptionInJoules(1)
//
//    new BeamVehicle(
//      powerTrain,
//      matsimVehicle,
//      CarVehicle,
//      Some(fuelCapacityInJoules),
//      Some(fuelCapacityInJoules),
//      refuelRateLimitInWatts
//    )
//  }

  //TODO: Identify the vehicles by type in xml
  def makeHouseholdVehicle(
    beamVehicles: TrieMap[Id[BeamVehicle], BeamVehicle],
    id: Id[Vehicle]
  ): Either[IllegalArgumentException, BeamVehicle] = {

    if (BeamVehicleType.isBicycleVehicle(id)) {
      Right(makeBicycle(id))
    } else {
      beamVehicles
        .get(id)
        .toRight(
          new IllegalArgumentException(s"Invalid vehicle id $id")
        )
    }
  }

  def getVehicleTypeById(
    id: String,
    vehicleTypes: java.util.Map[Id[VehicleType], VehicleType]
  ): Option[VehicleType] = {
    JavaConverters
      .mapAsScalaMap(vehicleTypes)
      .filter(idAndType => idAndType._2.getId.toString.equalsIgnoreCase(id))
      .values
      .headOption
  }

  def getVehicleTypeByDescription(
    description: String,
    vehicleTypes: java.util.Map[Id[VehicleType], VehicleType]
  ): Option[VehicleType] = {
    JavaConverters
      .mapAsScalaMap(vehicleTypes)
      .filter(idAndType => idAndType._2.getDescription.equalsIgnoreCase(description))
      .values
      .headOption
  }

}
