package beam.sim.vehicles
import beam.agentsim.agents.Population
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.BeamVehicleType.VehicleCategory
import beam.sim.BeamServices
import beam.sim.config.BeamConfig.Beam
import org.matsim.api.core.v01.{Coord, Scenario}

import scala.collection.mutable.ListBuffer
import scala.util.Random

case class DefaultVehiclesAdjustment(beamServices: BeamServices) extends VehiclesAdjustment {

  override def sampleVehicleTypesForHousehold(
    numVehicles: Int,
    vehicleCategory: VehicleCategory,
    householdIncome: Double,
    householdSize: Int,
    householdPopulation: Population,
    householdLocation: Coord
  ): List[BeamVehicleType] = {

    /*
    numVehicles of




    Let say i have vehicleCategory passed to the function is 'Car'
    In that case I go the beamServices.vehicleTypes
    and I find that there is only one BeamVehicleType of category 'car'
    So in that case i return list of BeamVehicleType of size = numVehicles of same category

    One method will just loop for 5 time how many times we want to call
     */

    var list: ListBuffer[BeamVehicleType] = ListBuffer[BeamVehicleType]()

    beamServices.vehicleTypes.values
      .filter { v => {
          /*v.vehicleCategory match {
            case Some(vc) => vc == vehicleCategory
            case None     => false
          }*/
          v.vehicleTypeId.equalsIgnoreCase(vehicleCategory.toString)
        }
      }
      .foreach {
        case (v: BeamVehicleType) => {
          list += v
        }
      }

    var listToReturn: ListBuffer[BeamVehicleType] = ListBuffer[BeamVehicleType]()

    if(list.size == 0) {
      beamServices.vehicleTypes.values.take(1).foreach(v =>
        for(i <- 1 to numVehicles){
          listToReturn += v.copy()
        }
      )
    }else{
      val random = new Random()
      for(i <- 1 to numVehicles){
        listToReturn += list(random.nextInt(list.size)).copy()
      }
    }

    listToReturn.toList
  }
}


