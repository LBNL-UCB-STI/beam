package beam.utils

import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.{Vehicle, Vehicles}

import scala.collection.JavaConverters

object BeamVehicleUtils {

  def makeBicycle(id: Id[Vehicle]): BeamVehicle = {
    //FIXME: Every person gets a Bicycle (for now, 5/2018)
    //TODO after refactorVehicleTypes
//    new BeamVehicle(
//      BicycleVehicle.powerTrainForBicycle,
//      BicycleVehicle.createMatsimVehicle(id),
//      None,
//      BicycleVehicle,
//      None,
//      None
//    )
    ???
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
    new BeamVehicle(id, powerTrain, vehicleAttribute, BeamVehicleType.getCarVehicle(), None)
  }

  //TODO: Identify the vehicles by type in xml
  def makeHouseholdVehicle(
    matsimVehicles: Vehicles,
    id: Id[Vehicle]
  ): Either[IllegalArgumentException, BeamVehicle] = {

    if (BeamVehicleType.isBicycleVehicle(id)) {
      Right(makeBicycle(id))
    } else {
      Right(makeCar(matsimVehicles, id))
    }
  }

}
