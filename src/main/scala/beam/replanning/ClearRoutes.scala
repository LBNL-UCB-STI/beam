package beam.replanning

import com.google.inject.Provider
import org.matsim.api.core.v01.population.{Leg, Plan}
import org.matsim.api.core.v01.replanning.PlanStrategyModule
import org.matsim.core.replanning.selectors.RandomPlanSelector
import org.matsim.core.replanning.{PlanStrategy, PlanStrategyImpl, ReplanningContext}

class ClearRoutes extends Provider[PlanStrategy] {
  override def get(): PlanStrategy = {
    new PlanStrategyImpl.Builder(new RandomPlanSelector())
      .addStrategyModule(new PlanStrategyModule {
        override def prepareReplanning(replanningContext: ReplanningContext): Unit = {}
        override def handlePlan(plan: Plan): Unit = {
          plan.getPlanElements.forEach {
            case leg: Leg =>
              leg.setRoute(null)
            case _ =>
          }
        }
        override def finishReplanning(): Unit = {}
      })
      .build()
  }

}