package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator.TripDataOrTrip
import beam.router.Modes
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import beam.sim.population.AttributesOfIndividual
import org.matsim.api.core.v01.population.Activity
import org.matsim.api.core.v01.population.Person

import scala.collection.mutable.ListBuffer

/**
  * BEAM
  */
class ModeChoiceTransitIfAvailable(val beamServices: BeamServices) extends ModeChoiceCalculator {

  override lazy val beamConfig: BeamConfig = beamServices.beamConfig

  override def clone(): ModeChoiceCalculator =
    new ModeChoiceTransitIfAvailable(beamServices)

  override def apply(
    alternatives: IndexedSeq[TripDataOrTrip],
    attributesOfIndividual: AttributesOfIndividual,
    destinationActivity: Option[Activity],
    person: Option[Person] = None
  ): Option[TripDataOrTrip] = {
    val containsTransitAlt = alternatives.zipWithIndex.collect {
      case (trip, idx) if extractTripClassifier(trip).isTransit => idx
    }
    if (containsTransitAlt.nonEmpty) {
      Some(alternatives(containsTransitAlt.head))
    } else if (alternatives.nonEmpty) {
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

object ModeChoiceTransitIfAvailable {}
