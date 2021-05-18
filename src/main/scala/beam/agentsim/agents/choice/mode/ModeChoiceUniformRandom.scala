package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator.TripDataOrTrip
import beam.router.Modes
import beam.sim.config.BeamConfig
import beam.sim.population.AttributesOfIndividual
import org.matsim.api.core.v01.population.{Activity, Person}

import scala.collection.mutable.ListBuffer

/**
  * BEAM
  */
class ModeChoiceUniformRandom(val beamConfig: BeamConfig) extends ModeChoiceCalculator {

  override def apply(
    alternatives: IndexedSeq[TripDataOrTrip],
    attributesOfIndividual: AttributesOfIndividual,
    destinationActivity: Option[Activity],
    person: Option[Person] = None
  ): Option[TripDataOrTrip] = {
    if (alternatives.nonEmpty) {
      Some(alternatives(chooseRandomAlternativeIndex(alternatives)))
    } else {
      None
    }
  }

  override def utilityOf(
    alternative: TripDataOrTrip,
    attributesOfIndividual: AttributesOfIndividual,
    destinationActivity: Option[Activity]
  ): Double = 0.0

  override def utilityOf(
    mode: Modes.BeamMode,
    cost: Double,
    time: Double,
    numTransfers: Int,
    transitOccupancyLevel: Double
  ): Double = 0.0

  override def computeAllDayUtility(
    trips: ListBuffer[TripDataOrTrip],
    person: Person,
    attributesOfIndividual: AttributesOfIndividual
  ): Double = 0.0
}
