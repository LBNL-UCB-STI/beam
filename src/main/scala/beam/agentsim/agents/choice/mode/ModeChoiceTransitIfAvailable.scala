package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.choice.logit.{MultinomialLogit, UtilityFunctionOperation}
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.router.Modes
import beam.router.Modes.BeamMode
import beam.router.model.EmbodiedBeamTrip
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import beam.sim.population.AttributesOfIndividual
import org.matsim.api.core.v01.population.Activity
import org.matsim.api.core.v01.population.Person

/**
  * BEAM
  */
class ModeChoiceTransitIfAvailable(val beamServices: BeamServices) extends ModeChoiceCalculator {

  override lazy val beamConfig: BeamConfig = beamServices.beamConfig

  override val modeChoiceLogit: MultinomialLogit[BeamMode, String] = new MultinomialLogit[BeamMode, String](
    {
      case mode if mode.isTransit => Some(Map("intercept" -> UtilityFunctionOperation("intercept", 1000.0)))
      case _                      => Option.empty
    },
    commonUtility
  )

  override def clone(): ModeChoiceCalculator =
    new ModeChoiceTransitIfAvailable(beamServices)

  override def apply(
    alternatives: IndexedSeq[EmbodiedBeamTrip],
    attributesOfIndividual: AttributesOfIndividual,
    destinationActivity: Option[Activity],
    originActivity: Option[Activity],
    person: Option[Person] = None
  ): Option[EmbodiedBeamTrip] = {
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

  override def utilityOf(
    alternative: EmbodiedBeamTrip,
    attributesOfIndividual: AttributesOfIndividual,
    destinationActivity: Option[Activity],
    originActivity: Option[Activity]
  ): Double = 0.0

  override def utilityOf(
    mode: Modes.BeamMode,
    cost: Double,
    time: Double,
    numTransfers: Int,
    transitOccupancyLevel: Double
  ): Double = 0.0

  override def computeAllDayUtility(
    trips: Map[EmbodiedBeamTrip, Map[String, Double]],
    person: Person,
    attributesOfIndividual: AttributesOfIndividual,
    overrideAttributes: Boolean = false
  ): Double = 0.0
}

object ModeChoiceTransitIfAvailable {}
