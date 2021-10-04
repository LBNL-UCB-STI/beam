package beam.sim.vehicles

import beam.agentsim.agents.Population
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.sim.BeamScenario
import org.apache.commons.math3.distribution.UniformRealDistribution
import org.matsim.api.core.v01.Coord

case class IncomeBasedVehiclesAdjustment(beamScenario: BeamScenario) extends VehiclesAdjustment {

  case class CategoryAttributeAndGroup(vehicleCategory: VehicleCategory, householdAttribute: String, group: String)

  private val vehicleTypesAndProbabilityByCategoryAndGroup =
    scala.collection.mutable.Map[CategoryAttributeAndGroup, Array[(BeamVehicleType, Double)]]()
  beamScenario.vehicleTypes.values.groupBy(_.vehicleCategory).foreach { case (cat, vehTypes) =>
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
    realDistribution: UniformRealDistribution
  ): List[BeamVehicleType] = {
    val matchedGroups =
      vehicleTypesAndProbabilityByCategoryAndGroup.keys.filter(x => isThisHouseholdInThisGroup(householdIncome, x))
    @SuppressWarnings(Array("UnsafeTraversableMethods"))
    val categoryAndGroup = if (matchedGroups.size > 1) {
      logger.warn(
        s"Multiple categories defined for household with income $householdIncome, choosing a default one"
      )
      matchedGroups.head
    } else if (matchedGroups.isEmpty) {
      logger.warn(
        s"No categories defined for household with income $householdIncome, choosing a default one"
      )
      vehicleTypesAndProbabilityByCategoryAndGroup.keys.head
    } else {
      matchedGroups.head
    }
    val vehTypeWithProbabilityOption = vehicleTypesAndProbabilityByCategoryAndGroup.get(categoryAndGroup)
    val vehTypeWithProbability: Array[(BeamVehicleType, Double)] = vehTypeWithProbabilityOption match {
      case Some(vtWithProb) => vtWithProb
      case _ =>
        logger.warn(
          s"There is no vehicle defined for group ${categoryAndGroup.group}, defaulting to ${vehicleTypesAndProbabilityByCategoryAndGroup.head._1.group}"
        )
        vehicleTypesAndProbabilityByCategoryAndGroup.head._2
    }
    (1 to numVehicles).map { _ =>
      val newRand = realDistribution.sample()
      val (vehType, _) = vehTypeWithProbability.find { case (_, prob) => prob >= newRand }.get
      vehType
    }.toList
  }

  override def sampleVehicleTypes(
    numVehicles: Int,
    vehicleCategory: VehicleCategory,
    realDistribution: UniformRealDistribution
  ): List[BeamVehicleType] = {
    val vehTypeWithProbabilityOption =
      vehicleTypesAndProbabilityByCategoryAndGroup.get(
        CategoryAttributeAndGroup(beam.agentsim.agents.vehicles.VehicleCategory.Car, "ridehail", "all")
      )
    val vehTypeWithProbability: Array[(BeamVehicleType, Double)] = vehTypeWithProbabilityOption match {
      case Some(vtWithProb) => vtWithProb
      case _ =>
        logger.warn(
          s"There is no vehicle defined for ridehail vehicles, defaulting to ${vehicleTypesAndProbabilityByCategoryAndGroup.head._1.group}"
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
  ): scala.collection.mutable.Map[CategoryAttributeAndGroup, Array[(BeamVehicleType, Double)]] = {
    val groupIDs = scala.collection.mutable.Map[CategoryAttributeAndGroup, Array[(BeamVehicleType, Double)]]()
    val groupIDlist: scala.collection.mutable.ListBuffer[CategoryAttributeAndGroup] =
      scala.collection.mutable.ListBuffer()
    val vehicleTypeAndProbabilityList: scala.collection.mutable.ListBuffer[(BeamVehicleType, Double)] =
      scala.collection.mutable.ListBuffer()
    vehTypes.foreach { vehType =>
      vehType.sampleProbabilityString.getOrElse("All").replaceAll("\\s", "").toLowerCase.split(";").foreach { group =>
        group.split('|') match {
          case Array(groupKey, values @ _*) =>
            values.foreach { value =>
              value.split(":") match {
                case Array(key, probability) =>
                  groupIDlist += CategoryAttributeAndGroup(category, groupKey, key)
                  vehicleTypeAndProbabilityList += ((vehType, probability.toDouble))
                case _ =>
                  logger.warn(
                    s"Badly formed category in vehicle adjustment: $value"
                  )
              }
            }
          case _ =>
            logger.warn(
              s"Badly formed vehicle sampling string in vehicle adjustment: $group"
            )
        }
      }
    }
    groupIDlist.zip(vehicleTypeAndProbabilityList).groupBy(_._1).map { case (groupID, vehicleTypeAndProbability) =>
      val probSum = vehicleTypeAndProbability.map(_._2._2).sum
      val cumulativeProbabilities = vehicleTypeAndProbability.map(_._2._2 / probSum).scan(0.0)(_ + _).drop(1)
      val vehicleTypes = vehicleTypeAndProbability.map(_._2._1)
      groupIDs += (groupID -> vehicleTypes.zip(cumulativeProbabilities).toArray)
    }
    groupIDs
  }

  def isThisHouseholdInThisGroup(
    householdIncome: Double,
    categoryAttributeAndGroup: CategoryAttributeAndGroup
  ): Boolean = {
    categoryAttributeAndGroup.householdAttribute match {
      case "income" =>
        categoryAttributeAndGroup.group.split("-") match {
          case Array(lowerBound, upperBound) =>
            (householdIncome / 1000 <= upperBound.toDouble) && (householdIncome / 1000 > lowerBound.toDouble)
          case _ =>
            logger.warn(
              s"Badly Formed vehicle sampling key ${categoryAttributeAndGroup.group} under group ${categoryAttributeAndGroup.householdAttribute}"
            )
            false
        }
      case "ridehail" =>
        false
      case _ =>
        logger.warn(
          s"Sample vehicles by ${categoryAttributeAndGroup.householdAttribute} not implemented yet"
        )
        false
    }
  }
}
