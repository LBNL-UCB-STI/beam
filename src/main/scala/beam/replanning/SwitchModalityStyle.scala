package beam.replanning

import java.util.Random

import com.google.inject.Provider
import org.matsim.api.core.v01.population.Plan
import org.matsim.api.core.v01.replanning.PlanStrategyModule
import org.matsim.core.replanning.selectors.RandomPlanSelector
import org.matsim.core.replanning.{PlanStrategy, PlanStrategyImpl, ReplanningContext}

class SwitchModalityStyle extends Provider[PlanStrategy] {
  override def get(): PlanStrategy = {
    new PlanStrategyImpl.Builder(new RandomPlanSelector())
      .addStrategyModule(new PlanStrategyModule {
        override def prepareReplanning(replanningContext: ReplanningContext): Unit = {}
        override def handlePlan(plan: Plan): Unit = {
          plan.getAttributes.putAttribute("modality-style", getRandomElement(List("class1", "class2", "class3", "class4", "class5", "class6"), new Random))
        }
        override def finishReplanning(): Unit = {}
      })
      .build()
  }

  def getRandomElement(list: Seq[String], random: Random): String = list(random.nextInt(list.length))
}
