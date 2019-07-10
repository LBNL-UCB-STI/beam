package beam.sim.vehicles

import beam.agentsim.agents.Population
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.sim.{BeamScenario, BeamServices}
import org.apache.commons.math3.distribution.UniformRealDistribution
import org.matsim.api.core.v01.Coord
import scala.collection.mutable.ArrayBuffer

case class UniformVehiclesAdjustment(beamScenario: BeamScenario) extends VehiclesAdjustment {

//  private val vehicleTypesAndProbabilitiesByCategoryAndGroup: Map[Int, (Array[(BeamVehicleType, Double)], String)] =
//    beamScenario.vehicleTypes.values.groupBy(_.vehicleCategory).map {
//      case (cat, vehTypes) =>
//        vehTypes.foreach( x =>
//          println(x.sampleProbabilityString.getOrElse("None"))
//        )
//  }

  private val vehicleTypesAndProbabilityByCategoryAndGroup = scala.collection.mutable.Map[(VehicleCategory, String),Array[(BeamVehicleType, Double)]]()
  beamScenario.vehicleTypes.values.groupBy(x => x.vehicleCategory).map {
    case (cat, vehTypes) =>
      val submap = getCategoryAndGroup(cat, vehTypes.toArray)
      vehicleTypesAndProbabilityByCategoryAndGroup ++= submap
  }

  private val vehicleTypesAndProbabilitiesByCategory: Map[(VehicleCategory, String), Array[(BeamVehicleType, Double)]] =
    beamScenario.vehicleTypes.values.groupBy(x => (x.vehicleCategory, matchCarUse(x.id.toString))).map {
      case (cat, vehTypes) =>
        val probSum = vehTypes.map(_.sampleProbabilityWithinCategory).sum
 //       val catAndGroup = vehTypes.foreach(x => getCategoryAndGroup(x.sampleProbabilityString.getOrElse("None")))
//        val catAndGroup = getCategoryAndGroup(cat, vehTypes)
        val cumulativeProbabilities = vehTypes
          .map(_.sampleProbabilityWithinCategory / probSum)
          .scan(0.0)(_ + _)
          .drop(1)
          .toList :+ 1.0
        val vehTypeWithProbability =
          vehTypes.zip(cumulativeProbabilities).map { case (vehType, prob) => (vehType, prob) }.toArray
        (cat, vehTypeWithProbability)
    }

  override def sampleVehicleTypesForHousehold(
    numVehicles: Int,
    vehicleCategory: VehicleCategory,
    householdIncome: Double,
    householdSize: Int,
    householdPopulation: Population,
    householdLocation: Coord,
    realDistribution: UniformRealDistribution,
  ): List[BeamVehicleType] = {
    val categoryAndGroup = getHouseholdIncomeGroup(householdIncome)
    val vehTypeWithProbability = vehicleTypesAndProbabilityByCategoryAndGroup(categoryAndGroup)
    (1 to numVehicles).map { _ =>
      val newRand = realDistribution.sample()
      val (vehType, _) = vehTypeWithProbability.find { case (_, prob) => prob >= newRand }.get
      vehType
    }.toList
  }

  private def getHouseholdIncomeGroup(
    householdIncome: Double
                                     ): (VehicleCategory, String) = {
    println(householdIncome)
    (beam.agentsim.agents.vehicles.VehicleCategory.Car, "LowIncome")
  }


  def getCategoryAndGroup(category: VehicleCategory, vehTypes: Array[BeamVehicleType]): scala.collection.mutable.Map[(VehicleCategory, String),Array[(BeamVehicleType, Double)]] = {
    var groupIDs = scala.collection.mutable.Map[(VehicleCategory, String), Array[(BeamVehicleType, Double)]]()
    var groupIDlist : scala.collection.mutable.ListBuffer[(VehicleCategory, String)] = scala.collection.mutable.ListBuffer()
    var vehicleTypeAndProbabilityList : scala.collection.mutable.ListBuffer[(BeamVehicleType, Double)] = scala.collection.mutable.ListBuffer()
    var probabilityList : scala.collection.mutable.ListBuffer[Double] = scala.collection.mutable.ListBuffer()
    vehTypes.foreach { vehType =>
      vehType.sampleProbabilityString.getOrElse("All").replaceAll("\\s", "").split(";").foreach { group =>
        val groupAndProbability = group.split(":")
        if (groupAndProbability.length == 2) {
          groupIDlist += ((category, groupAndProbability(0)))
          vehicleTypeAndProbabilityList += ((vehType,groupAndProbability(1).toDouble))
        }
      }
      groupIDlist.zip(vehicleTypeAndProbabilityList).groupBy( _._1).map {
        case (groupID, vehicleTypeAndProbability) =>
        val probSum = vehicleTypeAndProbability.map(_._2._2).sum
        val cumulativeProbabilities = vehicleTypeAndProbability.map(_._2._2 / probSum).scan(0.0)(_ + _).drop(1)
        val vehicleTypes = vehicleTypeAndProbability.map(_._2._1)
          groupIDs += (groupID -> vehicleTypes.zip(cumulativeProbabilities).toArray)
      }
    }
    groupIDs
  }

  override def sampleRideHailVehicleTypes(
    numVehicles: Int,
    vehicleCategory: VehicleCategory,
    realDistribution: UniformRealDistribution
  ): List[BeamVehicleType] = {
    val vehTypeWithProbability = vehicleTypesAndProbabilitiesByCategory.getOrElse(
      (vehicleCategory, "Ride Hail Vehicle"),
      vehicleTypesAndProbabilitiesByCategory(vehicleCategory, "Usage Not Set")
    )
    (1 to numVehicles).map { _ =>
      val newRand = realDistribution.sample()
      val (vehType, _) = vehTypeWithProbability.find { case (_, prob) => prob >= newRand }.get
      vehType
    }.toList
  }

  private def matchCarUse(vehicleTypeId: String): String = {
    vehicleTypeId.toString.split("_").headOption match {
      case Some(beamScenario.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypePrefix) =>
        "Ride Hail Vehicle"
      case _ => "Usage Not Set"
    }
  }
}
