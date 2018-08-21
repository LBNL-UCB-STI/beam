package beam.utils

import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.vehicles.BeamVehicleType.{BicycleVehicle, CarVehicle}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.{Vehicle, Vehicles}

import scala.collection.JavaConverters

object BeamVehicleUtils {

  def makeBicycle(id: Id[Vehicle]): BeamVehicle = {
    //FIXME: Every person gets a Bicycle (for now, 5/2018)
    new BeamVehicle(
      BicycleVehicle.powerTrainForBicycle,
      BicycleVehicle.createMatsimVehicle(id),
      None,
      BicycleVehicle,
      None,
      None
    )
  }

  def makeCar(matsimVehicles: Vehicles, id: Id[Vehicle]): BeamVehicle = {
    val matsimVehicle = JavaConverters.mapAsScalaMap(matsimVehicles.getVehicles)(id)

    val information = Option(matsimVehicle.getType.getEngineInformation)

    val vehicleAttribute = Option(matsimVehicles.getVehicleAttributes)

    val powerTrain = Powertrain.PowertrainFromMilesPerGallon(
      information
        .map(_.getGasConsumption)
        .getOrElse(Powertrain.AverageMilesPerGallon)
    )
    new BeamVehicle(powerTrain, matsimVehicle, vehicleAttribute, CarVehicle, None, None)
  }

  //TODO: Identify the vehicles by type in xml
  def makeHouseholdVehicle(
    matsimVehicles: Vehicles,
    id: Id[Vehicle]
  ): Either[IllegalArgumentException, BeamVehicle] = {
    if (BicycleVehicle.isVehicleType(id)) {
      Right(makeBicycle(id))
    } else {
      Right(makeCar(matsimVehicles, id))
    }
  }

}
