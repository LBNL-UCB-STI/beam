package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.household.HouseholdActor
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.router.Modes
import beam.router.model.EmbodiedBeamTrip
import beam.sim.BeamServices
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person

/**
  * BEAM
  */
class ModeChoiceTransitIfAvailable(val beamServices: BeamServices) extends ModeChoiceCalculator {

  override def clone(): ModeChoiceCalculator =
    new ModeChoiceTransitIfAvailable(beamServices)

  override def apply(alternatives: IndexedSeq[EmbodiedBeamTrip], attributesOfIndividual: HouseholdActor.AttributesOfIndividual): Option[EmbodiedBeamTrip] = {
    val containsTransitAlt = alternatives.zipWithIndex.collect {
      case (trip, idx) if trip.tripClassifier.isTransit => idx
    }
    if (containsTransitAlt.nonEmpty) {
      Some(alternatives(containsTransitAlt.head))
    } else if (alternatives.nonEmpty) {
      Some(alternatives(chooseRandomAlternativeIndex(alternatives)))
    } else {
      None
    }
  }

  override def utilityOf(alternative: EmbodiedBeamTrip, attributesOfIndividual: HouseholdActor.AttributesOfIndividual): Double = 0.0

  override def utilityOf(
    mode: Modes.BeamMode,
    cost: BigDecimal,
    time: BigDecimal,
    numTransfers: Int
  ): Double = 0.0
}

object ModeChoiceTransitIfAvailable {}
