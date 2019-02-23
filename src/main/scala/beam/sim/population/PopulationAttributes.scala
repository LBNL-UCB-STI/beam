package beam.sim.population

import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode._
import beam.router.model.{BeamLeg, EmbodiedBeamLeg}
import org.matsim.api.core.v01.Id
import org.matsim.households.{Household, IncomeImpl}
import org.matsim.households.Income.IncomePeriod
import org.matsim.api.core.v01.population._
import beam.sim.BeamServices

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
  def getVOT(embodiedBeamLeg: EmbodiedBeamLeg, beamServices: BeamServices): Double = {
    embodiedBeamLeg.beamLeg.mode match {
      case CAR | RIDE_HAIL | RIDE_HAIL_POOLED => getPathVOTT(embodiedBeamLeg.beamLeg, beamServices: BeamServices) * beamServices.getPooledFactor(embodiedBeamLeg.isPooledTrip, embodiedBeamLeg.beamVehicleTypeId)
      case _                                  => beamServices.getModeVotMultiplier(Some(embodiedBeamLeg.beamLeg.mode)) * embodiedBeamLeg.beamLeg.duration * valueOfTime
    }
  }

  // If we look at the path, we have multipliers for each link based on FF speed and current congestion
  private def getPathVOTT(beamLeg: BeamLeg, beamServices: BeamServices): Double = {
    valueOfTime * (beamLeg.travelPath.linkIds zip beamLeg.travelPath.linkTravelTime).map(x => beamServices.getSituationMultiplier(x._1,x._2) * x._2).sum
  }

  // If it's not a car mode, send it over to beamServices to get the mode VOTT multiplier from config
  def getModalVOTT(beamMode: BeamMode, beamServices: BeamServices): Double = {
    beamServices.getModeVotMultiplier(Some(beamMode)) * valueOfTime
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
