package beam.sim.population

import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode._
import beam.router.model.{BeamLeg, EmbodiedBeamLeg}
import org.matsim.api.core.v01.Id
import org.matsim.households.{Household, IncomeImpl}
import org.matsim.households.Income.IncomePeriod
import org.matsim.api.core.v01.population._
import beam.sim.BeamServices
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator._

import scala.collection.JavaConverters._
import scala.collection.mutable

sealed trait PopulationAttributes

case class AttributesOfIndividual(
  householdAttributes: HouseholdAttributes,
  modalityStyle: Option[String],
  isMale: Boolean,
  availableModes: Seq[BeamMode],
  valueOfTime: Double,
  age: Option[Int],
  income: Option[Double]
) extends PopulationAttributes {
  lazy val hasModalityStyle: Boolean = modalityStyle.nonEmpty

  // Get Value of Travel Time for a specific leg of a travel alternative:
  // If it is a car leg, we use link-specific multipliers, otherwise we just look at the entire leg travel time and mode
  def getVOT(
    embodiedBeamLeg: EmbodiedBeamLeg,
    modeMultipliers: mutable.Map[Option[BeamMode], Double],
    situationMultipliers: mutable.Map[(timeSensitivity, congestionLevel, roadwayType, automationLevel), Double],
    poolingMultipliers: mutable.Map[automationLevel, Double],
    beamServices: BeamServices,
    destinationActivity: Option[Activity]
  ): Double = {
    val isWorkTrip = destinationActivity match {
      case None =>
        false
      case Some(activity) =>
        activity.getType().equalsIgnoreCase("work")
    }
    embodiedBeamLeg.beamLeg.mode match {
      case CAR => // NOTE: Ride hail legs are classified as CAR mode. Retained both for flexibility (but could delete the other cases)
        if (embodiedBeamLeg.isRideHail) {
          if (embodiedBeamLeg.isPooledTrip) {
            getPooledFactor(embodiedBeamLeg, poolingMultipliers, beamServices) *
            getModeVotMultiplier(Option(RIDE_HAIL_POOLED), modeMultipliers) *
            unitConversionVOTT(embodiedBeamLeg.beamLeg.duration)
          } else {
            getModeVotMultiplier(Option(RIDE_HAIL), modeMultipliers) *
            unitConversionVOTT(embodiedBeamLeg.beamLeg.duration)
          }
        } else if (embodiedBeamLeg.asDriver) {
          // Situation multipliers are only relevant when the agent is driving
          getPathVotMultiplier(
            embodiedBeamLeg.beamLeg,
            situationMultipliers,
            beamServices,
            isWorkTrip,
            getAutomationLevel(embodiedBeamLeg, beamServices)
          ) *
          getModeVotMultiplier(Option(CAR), modeMultipliers) *
          unitConversionVOTT(embodiedBeamLeg.beamLeg.duration)
        } else {
          // Assume that not driving and not ridehail means CAV
          getModeVotMultiplier(Option(CAV), modeMultipliers) *
          unitConversionVOTT(embodiedBeamLeg.beamLeg.duration)
        }
      case RIDE_HAIL =>
        getModeVotMultiplier(Option(RIDE_HAIL), modeMultipliers) *
        unitConversionVOTT(embodiedBeamLeg.beamLeg.duration)
      case RIDE_HAIL_POOLED =>
        getPooledFactor(embodiedBeamLeg, poolingMultipliers, beamServices) *
        getModeVotMultiplier(Option(RIDE_HAIL_POOLED), modeMultipliers) *
        unitConversionVOTT(embodiedBeamLeg.beamLeg.duration)
      case _ =>
        getModeVotMultiplier(Option(embodiedBeamLeg.beamLeg.mode), modeMultipliers) *
        unitConversionVOTT(embodiedBeamLeg.beamLeg.duration)
    }
  }

  private def getAutomationLevel(embodiedBeamLeg: EmbodiedBeamLeg, beamServices: BeamServices): automationLevel = {
    // Use default if it exists, otherwise look up from vehicle ID
    val vehicleAutomationLevel = beamServices
      .getDefaultAutomationLevel()
      .getOrElse(beamServices.vehicleTypes(embodiedBeamLeg.beamVehicleTypeId).automationLevel)
    vehicleAutomationLevel match {
      case 1 => levelLE2
      case 2 => levelLE2
      case 3 => level3
      case 4 => level4
      case 5 => level5
      case _ => levelLE2
    }

  }
  private def getPathVotMultiplier(
    beamLeg: BeamLeg,
    situationMultipliers: mutable.Map[(timeSensitivity, congestionLevel, roadwayType, automationLevel), Double],
    beamServices: BeamServices,
    isWorkTrip: Boolean,
    vehicleAutomationLevel: automationLevel
  ): Double = {
    // Iterate over links in a path. Get average multiplier weighted by link travel time
    (beamLeg.travelPath.linkIds zip beamLeg.travelPath.linkTravelTime)
      .map(
        x =>
          getSituationMultiplier(x._1, x._2, isWorkTrip, situationMultipliers, vehicleAutomationLevel, beamServices) * x._2
      )
      .sum / beamLeg.duration
  }

  // Convert from seconds to hours and bring in person's base VOT
  def unitConversionVOTT(duration: Double): Double = {
    valueOfTime / 3600 * duration
  }

  def getModeVotMultiplier(
    beamMode: Option[BeamMode],
    modeMultipliers: mutable.Map[Option[BeamMode], Double]
  ): Double = {
    modeMultipliers.getOrElse(beamMode, 1.0)
  }

  private def getPooledFactor(
    embodiedBeamLeg: EmbodiedBeamLeg,
    poolingMultipliers: mutable.Map[automationLevel, Double],
    beamServices: BeamServices
  ): Double = {
    val vehicleAutomationLevel = getAutomationLevel(embodiedBeamLeg, beamServices)
    poolingMultipliers.getOrElse(vehicleAutomationLevel, 1.0)
  }

  private def getLinkCharacteristics(
    linkID: Int,
    travelTime: Double,
    beamServices: BeamServices
  ): (congestionLevel, roadwayType) = {
    // Note: cutoffs for congested (2/3 free flow speed) and highway (ff speed > 20 m/s) are arbitrary and could be inputs
    val currentLink = beamServices.networkHelper.getLink(linkID).get
    val freeSpeed: Double = currentLink.getFreespeed()
    val currentSpeed: Double = currentLink.getLength() / travelTime
    if (currentSpeed < 0.67 * freeSpeed) {
      if (freeSpeed > 20) {
        (highCongestion, highway)
      } else {
        (highCongestion, nonHighway)
      }
    } else {
      if (freeSpeed > 20) {
        (lowCongestion, highway)
      } else {
        (lowCongestion, nonHighway)
      }
    }
  }

  private def getSituationMultiplier(
    linkID: Int,
    travelTime: Double,
    isWorkTrip: Boolean = true,
    situationMultipliers: mutable.Map[(timeSensitivity, congestionLevel, roadwayType, automationLevel), Double],
    vehicleAutomationLevel: automationLevel,
    beamServices: BeamServices
  ): Double = {
    val sensitivity: timeSensitivity = if (isWorkTrip) {
      highSensitivity
    } else {
      lowSensitivity
    }
    val (congestion, roadway) = getLinkCharacteristics(linkID, travelTime, beamServices)
    situationMultipliers.getOrElse((sensitivity, congestion, roadway, vehicleAutomationLevel), 1.0)
  }

}

object AttributesOfIndividual {
  val EMPTY = AttributesOfIndividual(HouseholdAttributes.EMPTY, None, true, Seq(), 0.0, None, None)
}

case class HouseholdAttributes(
  householdIncome: Double,
  householdSize: Int,
  numCars: Int,
  numBikes: Int
) extends PopulationAttributes

object HouseholdAttributes {

  val EMPTY = HouseholdAttributes(0.0, 0, 0, 0)

  def apply(household: Household, vehicles: Map[Id[BeamVehicle], BeamVehicle]): HouseholdAttributes = {
    new HouseholdAttributes(
      Option(household.getIncome)
        .getOrElse(new IncomeImpl(0, IncomePeriod.year))
        .getIncome,
      household.getMemberIds.size(),
      household.getVehicleIds.asScala
        .map(id => vehicles(id))
        .count(_.beamVehicleType.id.toString.toLowerCase.contains("car")),
      household.getVehicleIds.asScala
        .map(id => vehicles(id))
        .count(_.beamVehicleType.id.toString.toLowerCase.contains("bike"))
    )
  }
}
