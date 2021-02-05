package beam.replanning

import javax.inject.Inject
import org.matsim.api.core.v01.population.{HasPlansAndId, Person, Plan}
import org.slf4j.LoggerFactory

/**
  * A dummy PlanStrategyAdopter that will keep modes from changing one iteration to the next IF the
  * following config parameters are set as follows:
  *
  * beam.replanning.maxAgentPlanMemorySize = 1
  * beam.replanning.Module_1 = "StaticModes"
  * beam.replanning.ModuleProbability_1 = 1.0
  * beam.replanning.ModuleProbability_2 = 0.0
  * beam.replanning.ModuleProbability_3 = 0.0
  * beam.replanning.ModuleProbability_4 = 0.0
  */
class StaticModes @Inject()() extends PlansStrategyAdopter {

  private val log = LoggerFactory.getLogger(classOf[StaticModes])

  override def run(person: HasPlansAndId[Plan, Person]): Unit = {
    // To keep modes static, simply do nothing here and set config parameters as shown above
  }
}
