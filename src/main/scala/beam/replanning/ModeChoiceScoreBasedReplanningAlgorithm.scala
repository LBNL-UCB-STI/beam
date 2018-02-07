package beam.replanning

import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import org.matsim.core.population.algorithms.PlanAlgorithm

/**
  * Extend this abstract class for all mode-choice based replanning algorithms. It is assumed that the correct
  * [[ModeChoiceCalculator]] instance will be passed in as part of the general
  * [[beam.replanning.utilitybased.UtilityBasedModeChoice]] strategy.
  *
  * @param modeChoiceCalculator a configurable function to compute the mode choice that provides maximum
  *                             utility based on a the mode in the [[beam.agentsim.agents.planning.BeamPlan]]
  */
//TODO[saf]: make sure that the above invariant holds true
abstract class ModeChoiceScoreBasedReplanningAlgorithm(modeChoiceCalculator: ModeChoiceCalculator)
  extends PlanAlgorithm {

}
