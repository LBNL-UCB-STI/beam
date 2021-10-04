package beam.utils

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.sim.config.BeamConfig
import org.matsim.api.core.v01.Id

import scala.collection.mutable.ListBuffer

object InputConsistencyCheck {

  /**
    *  Right now we need to check if the values from
    *
    *    beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId
    *    beam.agentsim.agents.vehicles.dummySharedCar.vehicleTypeId
    *
    *  are present in vehicle types from file
    *
    *    beam.agentsim.agents.vehicles.vehicleTypesFilePath
    */
  def checkVehicleTypes(
    vehicleTypes: Set[Id[BeamVehicleType]],
    rideHailTypeId: String,
    dummySharedCarTypeId: String
  ): List[String] = {
    val errors = ListBuffer[String]()
    val stringTypes = vehicleTypes.map(_.toString)
    val vehicleTypeString = vehicleTypes.mkString(",")
    if (!stringTypes.contains(rideHailTypeId)) {
      errors.append(
        s"beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId '$rideHailTypeId' " +
        s"is not in vehicleTypes [$vehicleTypeString]"
      )
    }
    if (!stringTypes.contains(dummySharedCarTypeId)) {
      errors.append(
        s"beam.agentsim.agents.vehicles.dummySharedCar.vehicleTypeId '$dummySharedCarTypeId' " +
        s"is not in vehicleTypes [$vehicleTypeString]"
      )
    }
    errors.toList
  }

  def checkConsistency(beamConfig: BeamConfig): List[String] = {
    val vehicleTypes =
      BeamVehicleUtils.readBeamVehicleTypeFile(beamConfig.beam.agentsim.agents.vehicles.vehicleTypesFilePath)
    checkVehicleTypes(
      vehicleTypes.keySet,
      beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId,
      beamConfig.beam.agentsim.agents.vehicles.dummySharedCar.vehicleTypeId
    )
  }

}
