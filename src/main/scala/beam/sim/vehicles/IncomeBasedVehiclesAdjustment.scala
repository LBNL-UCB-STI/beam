package beam.sim.vehicles

import beam.agentsim.agents.Population
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.sim.{BeamScenario, BeamServices}
import org.apache.commons.math3.distribution.UniformRealDistribution
import org.matsim.api.core.v01.Coord

case class IncomeBasedVehiclesAdjustment(beamScenario: BeamScenario) extends VehiclesAdjustment {

  private val vehicleTypesAndProbabilityByCategoryAndGroup =
    scala.collection.mutable.Map[(VehicleCategory, (String, String)), Array[(BeamVehicleType, Double)]]()
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
    val matchedGroups =
      vehicleTypesAndProbabilityByCategoryAndGroup.keys.filter(x => isThisHouseholdInThisGroup(householdIncome, x._2))
    if (matchedGroups.size > 1) {
      logger.warn(
        s"Multiple categories defined for household with income ${householdIncome}, choosing a default one"
      )
    }
    val categoryAndGroup = matchedGroups.head
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
      vehicleTypesAndProbabilityByCategoryAndGroup.get(
        (beam.agentsim.agents.vehicles.VehicleCategory.Car, ("ridehail", "all"))
      )
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

  private def getCategoryAndGroup(
    category: VehicleCategory,
    vehTypes: Array[BeamVehicleType]
  ): scala.collection.mutable.Map[(VehicleCategory, (String, String)), Array[(BeamVehicleType, Double)]] = {
    var groupIDs = scala.collection.mutable.Map[(VehicleCategory, (String, String)), Array[(BeamVehicleType, Double)]]()
    var groupIDlist: scala.collection.mutable.ListBuffer[(VehicleCategory, (String, String))] =
      scala.collection.mutable.ListBuffer()
    var vehicleTypeAndProbabilityList: scala.collection.mutable.ListBuffer[(BeamVehicleType, Double)] =
      scala.collection.mutable.ListBuffer()
    vehTypes.foreach { vehType =>
      vehType.sampleProbabilityString.getOrElse("All").replaceAll("\\s", "").toLowerCase.split(";").foreach { group =>
        val keyAndValues = group.split('|')
        if (keyAndValues.length >= 2) {
          val groupKey = keyAndValues(0)
          for (i <- 1 until keyAndValues.length) {
            val keyAndProb = keyAndValues(i).split(":")
            if (keyAndProb.length == 2) {
              groupIDlist += ((category, (groupKey, keyAndProb(0))))
              vehicleTypeAndProbabilityList += ((vehType, keyAndProb(1).toDouble))
            }
          }
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

  def isThisHouseholdInThisGroup(householdIncome: Double, groupAndKey: (String, String)): Boolean = {
    if (groupAndKey._1.equalsIgnoreCase("income")) {
      val bounds = groupAndKey._2.split("-")
      if (bounds.length == 2) {
        (householdIncome / 1000 <= bounds(1).toDouble) && (householdIncome / 1000 > bounds(0).toDouble)
      } else {
        logger.warn(
          s"Badly Formed vehicle sampling key ${groupAndKey._2} under group ${groupAndKey._1}"
        )
        false
      }
    } else false

  }
}
