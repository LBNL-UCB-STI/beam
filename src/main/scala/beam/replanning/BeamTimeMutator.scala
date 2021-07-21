package beam.replanning

import java.util.Random
import javax.inject.Inject

import org.matsim.api.core.v01.population.{HasPlansAndId, Person, Plan}
import org.matsim.core.config.Config
import org.matsim.core.population.algorithms.TripPlanMutateTimeAllocation
import org.matsim.core.replanning.selectors.ExpBetaPlanSelector
import org.matsim.core.router.StageActivityTypes
import org.slf4j.LoggerFactory

class BeamTimeMutator @Inject() (config: Config) extends PlansStrategyAdopter {

  private val log = LoggerFactory.getLogger(classOf[BeamTimeMutator])

  override def run(person: HasPlansAndId[Plan, Person]): Unit = {
    log.debug("Before Replanning BeamTimeMutator: Person-" + person.getId + " - " + person.getPlans.size())

    ReplanningUtil.makeExperiencedMobSimCompatible(person)
    ReplanningUtil.copyRandomPlanAndSelectForMutation(person.getSelectedPlan.getPerson)

    val stageActivityTypes = new StageActivityTypes {
      override def isStageActivity(activityType: String): Boolean = false
    }

    new TripPlanMutateTimeAllocation(
      stageActivityTypes,
      config.timeAllocationMutator().getMutationRange,
      config.timeAllocationMutator().isAffectingDuration,
      new Random(config.global().getRandomSeed)
    ).run(person.getSelectedPlan)

    log.debug("After Replanning BeamTimeMutator: Person-" + person.getId + " - " + person.getPlans.size())
  }
}
