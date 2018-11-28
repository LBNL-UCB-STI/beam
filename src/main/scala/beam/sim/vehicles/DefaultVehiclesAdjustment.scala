package beam.sim.vehicles
import beam.agentsim.agents.Population
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.BeamVehicleType.VehicleCategory
import beam.replanning.BeamExpBeta
import beam.sim.BeamServices
import beam.sim.config.BeamConfig.Beam
import org.matsim.api.core.v01.{Coord, Scenario}

import scala.collection.mutable.ListBuffer
import scala.util.Random

case class DefaultVehiclesAdjustment(beamServices: BeamServices) extends VehiclesAdjustment {

  override def sampleVehicleTypesForHousehold(numVehicles: Int, vehicleCategory: VehicleCategory,
    householdIncome: Double, householdSize: Int, householdPopulation: Population, householdLocation: Coord
  ): List[BeamVehicleType] = {

    var listToReturn: ListBuffer[BeamVehicleType] = ListBuffer[BeamVehicleType]()
    var list: ListBuffer[BeamVehicleType] = ListBuffer[BeamVehicleType]()

    beamServices.vehicleTypes.values
      .filter { v =>
        v.vehicleCategory match {
          case Some(vc) => vc == vehicleCategory
          case None     => false
        }
      }
      .foreach {
        case (v: BeamVehicleType) => list += v
      }

    val bvt: BeamVehicleType =
      if(list.size == 0) throw new Exception("There are no vehicle types for the given category")
      else if(list.size == 1) beamServices.vehicleTypes.values.take(1).toList.head
      else{

        val defaultVehicleTypes = list.filter(l => l.vehicleCategory.contains("default"))
        val matchedVehicleTypes = list.filter(l => l.vehicleCategory.get == vehicleCategory)

        if(defaultVehicleTypes.size > 0) defaultVehicleTypes(0)
        else if(matchedVehicleTypes.size > 0) matchedVehicleTypes(0)
        else list.take(1)(0)
      }

    for(i <- 1 to numVehicles) listToReturn += bvt.copy()

    listToReturn.toList
  }
}


