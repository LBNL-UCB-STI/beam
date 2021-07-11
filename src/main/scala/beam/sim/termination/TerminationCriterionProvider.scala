package beam.sim.termination

import beam.sim.BeamServices
import beam.sim.config.BeamConfigHolder
import com.google.inject.{Inject, Provider}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.config.groups.ControlerConfigGroup
import org.matsim.core.controler.TerminationCriterion

/** Provider class for TerminationCriteria */
class TerminationCriterionProvider @Inject() (
  controlerConfigGroup: ControlerConfigGroup,
  beamConfigHolder: BeamConfigHolder,
  eventsManager: EventsManager,
  beamServices: BeamServices
) extends Provider[TerminationCriterion] {

  override def get(): TerminationCriterion = {
    val terminationCriterionName = beamConfigHolder.beamConfig.beam.sim.termination.criterionName

    val terminationCriterionTry = beamServices.beamCustomizationAPI.getTerminationCriterionFactory.create(
      controlerConfigGroup,
      beamConfigHolder,
      eventsManager,
      terminationCriterionName
    )

    val terminationCriterion = terminationCriterionTry.recoverWith { case exception: Exception =>
      throw new IllegalStateException(s"Unidentified termination criterion: `$terminationCriterionName`", exception)
    }.get

    terminationCriterion
  }
}
