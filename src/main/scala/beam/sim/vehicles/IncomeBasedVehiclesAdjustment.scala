package beam.sim.vehicles

import beam.agentsim.agents.Population
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.sim.{BeamScenario, BeamServices}
import org.apache.commons.math3.distribution.UniformRealDistribution
import org.matsim.api.core.v01.Coord

case class IncomeBasedVehiclesAdjustment(beamScenario: BeamScenario) extends VehiclesAdjustment {

  private val vehicleTypesAndProbabilityByCategoryAndGroup =
    scala.collection.mutable.Map[(VehicleCategory, String), Array[(BeamVehicleType, Double)]]()
  beamScenario.vehicleTypes.values.groupBy(x => x.vehicleCategory).map {
    case (cat, vehTypes) =>
      val submap = getCategoryAndGroup(cat, vehTypes.toArray)
      vehicleTypesAndProbabilityByCategoryAndGroup ++= submap
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
    val vehTypeWithProbabilityOption = vehicleTypesAndProbabilityByCategoryAndGroup.get(categoryAndGroup)
    val vehTypeWithProbability: Array[(BeamVehicleType, Double)] = vehTypeWithProbabilityOption match {
      case Some(vtWithProb) => vtWithProb
      case _ =>
        logger.warn(
          s"There is no vehicle defined for group ${categoryAndGroup._2}, defaulting to ${vehicleTypesAndProbabilityByCategoryAndGroup.head._1._2}"
        )
        vehicleTypesAndProbabilityByCategoryAndGroup.head._2
    }
    (1 to numVehicles).map { _ =>
      val newRand = realDistribution.sample()
      val (vehType, _) = vehTypeWithProbability.find { case (_, prob) => prob >= newRand }.get
      vehType
    }.toList
  }

  override def sampleRideHailVehicleTypes(
    numVehicles: Int,
    vehicleCategory: VehicleCategory,
    realDistribution: UniformRealDistribution
  ): List[BeamVehicleType] = {
    val vehTypeWithProbabilityOption =
      vehicleTypesAndProbabilityByCategoryAndGroup.get((beam.agentsim.agents.vehicles.VehicleCategory.Car, "ridehail"))
    val vehTypeWithProbability: Array[(BeamVehicleType, Double)] = vehTypeWithProbabilityOption match {
      case Some(vtWithProb) => vtWithProb
      case _ =>
        logger.warn(
          s"There is no vehicle defined for ridehail vehicles, defaulting to ${vehicleTypesAndProbabilityByCategoryAndGroup.head._1._2}"
        )
        vehicleTypesAndProbabilityByCategoryAndGroup.head._2
    }
    (1 to numVehicles).map { _ =>
      val newRand = realDistribution.sample()
      val (vehType, _) = vehTypeWithProbability.find { case (_, prob) => prob >= newRand }.get
      vehType
    }.toList
  }

  private def getHouseholdIncomeGroup(
    householdIncome: Double
  ): (VehicleCategory, String) = {
    val incomeBin = householdIncome match {
      case inc if inc <= 17000                 => "inc17"
      case inc if inc > 17000 && inc <= 36000  => "inc36"
      case inc if inc > 36000 && inc <= 61000  => "inc61"
      case inc if inc > 61000 && inc <= 86000  => "inc86"
      case inc if inc > 86000 && inc <= 133000 => "inc133"
      case inc if inc > 133000                 => "inc327"
    }
    (beam.agentsim.agents.vehicles.VehicleCategory.Car, incomeBin)
  }

  private def getCategoryAndGroup(
    category: VehicleCategory,
    vehTypes: Array[BeamVehicleType]
  ): scala.collection.mutable.Map[(VehicleCategory, String), Array[(BeamVehicleType, Double)]] = {
    var groupIDs = scala.collection.mutable.Map[(VehicleCategory, String), Array[(BeamVehicleType, Double)]]()
    var groupIDlist: scala.collection.mutable.ListBuffer[(VehicleCategory, String)] =
      scala.collection.mutable.ListBuffer()
    var vehicleTypeAndProbabilityList: scala.collection.mutable.ListBuffer[(BeamVehicleType, Double)] =
      scala.collection.mutable.ListBuffer()
    vehTypes.foreach { vehType =>
      vehType.sampleProbabilityString.getOrElse("All").replaceAll("\\s", "").toLowerCase.split(";").foreach { group =>
        val groupAndProbability = group.split(":")
        if (groupAndProbability.length == 2) {
          groupIDlist += ((category, groupAndProbability(0)))
          vehicleTypeAndProbabilityList += ((vehType, groupAndProbability(1).toDouble))
        }
      }
      groupIDlist.zip(vehicleTypeAndProbabilityList).groupBy(_._1).map {
        case (groupID, vehicleTypeAndProbability) =>
          val probSum = vehicleTypeAndProbability.map(_._2._2).sum
          val cumulativeProbabilities = vehicleTypeAndProbability.map(_._2._2 / probSum).scan(0.0)(_ + _).drop(1)
          val vehicleTypes = vehicleTypeAndProbability.map(_._2._1)
          groupIDs += (groupID -> vehicleTypes.zip(cumulativeProbabilities).toArray)
      }
    }
    groupIDs
  }

}
