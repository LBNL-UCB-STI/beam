package beam.replanning.utilitybased

import javax.inject.Inject

import beam.agentsim.agents.memberships.HouseholdMembershipAllocator
import beam.sim.BeamServices
import com.google.inject.Provider
import org.apache.log4j.Logger
import org.matsim.api.core.v01.Scenario
import org.matsim.core.config.Config
import org.matsim.core.population.algorithms.PlanAlgorithm
import org.matsim.core.replanning.modules.AbstractMultithreadedModule
import org.matsim.core.replanning.selectors.RandomPlanSelector
import org.matsim.core.replanning.{PlanStrategy, PlanStrategyImpl}

class UtilityBasedModeChoice @Inject()(config: Config, beamServices: BeamServices, scenario: Scenario) extends Provider[PlanStrategy] {

  private val log = Logger.getLogger(classOf[UtilityBasedModeChoice])
  val householdMembershipAllocator = HouseholdMembershipAllocator(scenario)

  if (!config.planCalcScore().isMemorizingExperiencedPlans) {
    throw new RuntimeException("Must memorize experienced plans for this to work.")
  }

  override def get(): PlanStrategy = {
    val strategy = new PlanStrategyImpl.Builder(new RandomPlanSelector())
    strategy.addStrategyModule(new AbstractMultithreadedModule(config.global()) {
      override def getPlanAlgoInstance: PlanAlgorithm =
        new ChangeModeForTour(beamServices, householdMembershipAllocator,scenario.getVehicles)
    })
    strategy.build()
  }
}
