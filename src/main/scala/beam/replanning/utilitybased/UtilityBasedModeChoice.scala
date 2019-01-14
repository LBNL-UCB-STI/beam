package beam.replanning.utilitybased

import beam.agentsim.agents.memberships.HouseholdMembershipAllocator
import beam.sim.BeamServices
import com.google.inject.Provider
import javax.inject.Inject

import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.population.Plan
import org.matsim.api.core.v01.replanning.PlanStrategyModule
import org.matsim.core.config.Config
import org.matsim.core.replanning.selectors.RandomPlanSelector
import org.matsim.core.replanning.{PlanStrategy, PlanStrategyImpl, ReplanningContext}

class UtilityBasedModeChoice @Inject()(
  config: Config,
  beamServices: BeamServices,
  scenario: Scenario
) extends Provider[PlanStrategy] {

  val householdMembershipAllocator =
    HouseholdMembershipAllocator(scenario.getHouseholds, scenario.getPopulation)
  val chainBasedModes: Set[String] = Set[String]("car")

  val chainBasedTourVehicleAllocator = ChainBasedTourVehicleAllocator(
    scenario.getVehicles,
    householdMembershipAllocator,
    chainBasedModes
  )

  if (!config.planCalcScore().isMemorizingExperiencedPlans) {
    throw new RuntimeException(
      s"Must memorize experienced plans for ${this.getClass.getSimpleName} to work."
    )
  }

  override def get(): PlanStrategy = {
    val strategy = new PlanStrategyImpl.Builder(new RandomPlanSelector())
    strategy.addStrategyModule(new PlanStrategyModule() {
      val changeModeForTour: ChangeModeForTour =
        new ChangeModeForTour(beamServices, chainBasedTourVehicleAllocator)

      override def handlePlan(plan: Plan): Unit =
        changeModeForTour.run(plan)

      override def finishReplanning(): Unit = {}

      override def prepareReplanning(replanningContext: ReplanningContext): Unit = {}
    })
    strategy.build()
  }
}
