package beam.utils

import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.vehicles.BeamVehicleType.{BicycleVehicle, CarVehicle}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.{Vehicle, VehicleType, Vehicles}

import scala.collection.JavaConverters

object BeamVehicleUtils {

  def makeBicycle(id: Id[Vehicle]): BeamVehicle = {
    //FIXME: Every person gets a Bicycle (for now, 5/2018)
    new BeamVehicle(
      BicycleVehicle.powerTrainForBicycle,
      BicycleVehicle.createMatsimVehicle(id),
      BicycleVehicle,
      None,
      None,
      None
    )
  }

  def makeCar(
    matsimVehicle: Vehicle,
    vehicleRangeInMeters: Double,
    refuelRateLimitInWatts: Option[Double]
  ): BeamVehicle = {
    val engineInformation = Option(matsimVehicle.getType.getEngineInformation)

    val powerTrain = engineInformation match {
      case Some(info) =>
        Powertrain(info)
      case None =>
        Powertrain.PowertrainFromMilesPerGallon(Powertrain.AverageMilesPerGallon)
    }

    val fuelCapacityInJoules = vehicleRangeInMeters * powerTrain.estimateConsumptionInJoules(1)

    new BeamVehicle(
      powerTrain,
      matsimVehicle,
      CarVehicle,
      Some(fuelCapacityInJoules),
      Some(fuelCapacityInJoules),
      refuelRateLimitInWatts
    )
  }

  def makeCar(
    matsimVehicles: Vehicles,
    id: Id[Vehicle],
    vehicleRangeInMeters: Double,
    refuelRateLimitInWatts: Option[Double]
  ): BeamVehicle = {
    makeCar(matsimVehicles.getVehicles.get(id), vehicleRangeInMeters, refuelRateLimitInWatts)
  }

  //TODO: Identify the vehicles by type in xml
  def makeHouseholdVehicle(
    matsimVehicles: Vehicles,
    id: Id[Vehicle],
    vehicleRangeInM: Double = Double.MaxValue,
    refuelRateLimitInWatts: Option[Double]
  ): Either[IllegalArgumentException, BeamVehicle] = {
    if (BicycleVehicle.isVehicleType(id)) {
      Right(makeBicycle(id))
    } else {
      Right(makeCar(matsimVehicles, id, vehicleRangeInM, refuelRateLimitInWatts))
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
