package beam.utils

import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.{Vehicle, Vehicles}

import scala.collection.JavaConverters
import scala.collection.concurrent.TrieMap

object BeamVehicleUtils {

  def makeBicycle(id: Id[Vehicle]): BeamVehicle = {
    //FIXME: Every person gets a Bicycle (for now, 5/2018)
//    new BeamVehicle(
//      BicycleVehicle.powerTrainForBicycle,
//      BicycleVehicle.createMatsimVehicle(id),
//      None,
//      BicycleVehicle,
//      None,
//      None
//    )

    val bvt = BeamVehicleType.defaultBicycleBeamVehicleType
    val beamVehicleId = BeamVehicle.createId(id, Some("bike"))
    val powertrain = Option(bvt.primaryFuelConsumptionInJoule)
      .map(new Powertrain(_))
      .getOrElse(Powertrain.PowertrainFromMilesPerGallon(Powertrain.AverageMilesPerGallon))
    new BeamVehicle(
      beamVehicleId,
      powertrain,
      None,
      bvt,
      None
    )
  }

  //TODO not used
//  def makeCar(beamVehicles: Vehicles, id: Id[Vehicle]): BeamVehicle = {
//    val matsimVehicle = JavaConverters.mapAsScalaMap(beamVehicles.getVehicles)(id)
//
//    val information = Option(matsimVehicle.getType.getEngineInformation)
//
//    val vehicleAttribute = Option(beamVehicles.getVehicleAttributes)
//
//    val powerTrain = Powertrain.PowertrainFromMilesPerGallon(
//      information
//        .map(_.getGasConsumption)
//        .getOrElse(Powertrain.AverageMilesPerGallon)
//    )
//    new BeamVehicle(id, powerTrain, vehicleAttribute, BeamVehicleType.getCarVehicle(), None)
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
          new IllegalArgumentException("Invalid vehicle id")
        )
//      Right(makeCar(beamVehicles, id))
    }
  }

}
