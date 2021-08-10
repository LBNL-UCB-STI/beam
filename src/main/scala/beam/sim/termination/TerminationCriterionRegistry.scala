package beam.sim.termination

import beam.sim.termination.TerminationCriterionRegistry.TerminationCriterionName
import beam.utils.Registry

object TerminationCriterionRegistry {

  trait TerminationCriterionName

}

/**
  * List of default termination criteria.
  */
object DefaultTerminationCriterionRegistry extends Registry[TerminationCriterionName] {

  case object TerminateAtFixedIterationNumber extends TerminationCriterionName
  case object TerminateAtRideHailFleetStoredElectricityConvergence extends TerminationCriterionName

  override val entries = Set(TerminateAtFixedIterationNumber, TerminateAtRideHailFleetStoredElectricityConvergence)
}
