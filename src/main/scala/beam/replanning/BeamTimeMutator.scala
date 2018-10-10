package beam.replanning

import java.util.Random
import javax.inject.Inject

import org.matsim.api.core.v01.population.{HasPlansAndId, Leg, Person, Plan}
import org.matsim.core.config.Config
import org.matsim.core.population.algorithms.TripPlanMutateTimeAllocation
import org.matsim.core.replanning.{PlanStrategy, ReplanningContext}
import org.matsim.core.router.StageActivityTypes

class BeamTimeMutator @Inject()(config: Config) extends PlanStrategy {
  override def init(replanningContext: ReplanningContext): Unit = {}

  override def run(person: HasPlansAndId[Plan, Person]): Unit = {
    ReplanningUtil.updateAndAddExperiencedPlan(person)
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
  }

  override def finish(): Unit = {}
}
