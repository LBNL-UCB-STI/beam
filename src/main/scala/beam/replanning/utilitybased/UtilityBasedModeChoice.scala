package beam.replanning.utilitybased

import javax.inject.Inject

import beam.sim.BeamServices
import com.google.inject.Provider
import org.apache.log4j.Logger
import org.matsim.core.config.Config
import org.matsim.core.replanning.selectors.RandomPlanSelector
import org.matsim.core.replanning.{PlanStrategy, PlanStrategyImpl}

class UtilityBasedModeChoice @Inject()(config: Config, beamServices: BeamServices) extends Provider[PlanStrategy] {

  private val log = Logger.getLogger(classOf[UtilityBasedModeChoice])


  if(!config.planCalcScore().isMemorizingExperiencedPlans) {
    throw new RuntimeException("Must memorize experienced plans for this to work.")
  }

  override def get(): PlanStrategy = {
    val strategy = new PlanStrategyImpl.Builder(new RandomPlanSelector())
    strategy.addStrategyModule(new UtilityBasedModeChoiceModule(config.global(), beamServices))
    strategy.build()
  }
}
