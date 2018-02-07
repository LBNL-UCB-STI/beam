package beam.replanning.utilitybased

import beam.agentsim.agents.choice.mode.ModeChoiceMultinomialLogit
import beam.agentsim.agents.planning.BeamPlan
import beam.replanning.ModeChoiceScoreBasedReplanningAlgorithm
import org.matsim.api.core.v01.population.Plan

class MultinomialLogitScoringAlgorithm(modeChoiceMultinomialLogit: ModeChoiceMultinomialLogit) extends ModeChoiceScoreBasedReplanningAlgorithm(modeChoiceMultinomialLogit){
  override def run(plan: Plan): Unit = {
    val beamPlan = BeamPlan(plan)
    beamPlan.tours.flatMap(tour=>tour.trips.map(trip=>trip.leg))

  }
}
