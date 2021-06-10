package beam.api.sim.termination

import beam.api.agentsim.agents.ridehail.charging.StallAssignmentStrategyFactory
import beam.sim.config.BeamConfigHolder
import beam.sim.termination.{
  CustomTerminateAtFixedIterationNumber,
  DefaultTerminationCriterionRegistry,
  TerminateAtRideHailFleetStoredElectricityConvergence
}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.config.groups.ControlerConfigGroup
import org.matsim.core.controler.TerminationCriterion

import scala.util.Try

/**
  * API defining [[TerminationCriterionFactory]] and its default implementation ([[DefaultTerminationCriterionFactory]]), which
  * allows to instantiate from a variety of termination criteria ([[TerminationCriterion]]) currently available in BEAM. In order to
  * add new custom termination criterion, a new [[TerminationCriterionFactory]] can be implemented similar to [[DefaultTerminationCriterionFactory]] and
  * [[DefaultTerminationCriterionFactory]] can be used as a delegate to access the existing termination criteria.
  */
trait TerminationCriterionFactory {

  def create(
    controlerConfigGroup: ControlerConfigGroup,
    beamConfigHolder: BeamConfigHolder,
    eventsManager: EventsManager,
    terminationCriterionName: String
  ): Try[TerminationCriterion]

}

/**
  * Default implementation of [[TerminationCriterionFactory]].
  */
class DefaultTerminationCriterionFactory extends TerminationCriterionFactory {
  override def create(
    controlerConfigGroup: ControlerConfigGroup,
    beamConfigHolder: BeamConfigHolder,
    eventsManager: EventsManager,
    terminationCriterionName: String
  ): Try[TerminationCriterion] = {
    Try {
      DefaultTerminationCriterionRegistry.withName(
        terminationCriterionName
      ) match {
        case DefaultTerminationCriterionRegistry.TerminateAtFixedIterationNumber =>
          new CustomTerminateAtFixedIterationNumber(controlerConfigGroup, beamConfigHolder)
        case DefaultTerminationCriterionRegistry.TerminateAtRideHailFleetStoredElectricityConvergence =>
          new TerminateAtRideHailFleetStoredElectricityConvergence(beamConfigHolder, eventsManager)
        case x =>
          throw new IllegalStateException(s"Unidentified termination criterion: `$x`")
      }
    }
  }

}
