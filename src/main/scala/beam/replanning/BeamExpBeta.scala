package beam.replanning

import javax.inject.Inject
import org.matsim.api.core.v01.population.{HasPlansAndId, Person, Plan}
import org.matsim.core.config.Config
import org.matsim.core.replanning.selectors.ExpBetaPlanSelector

class BeamExpBeta @Inject()(config: Config) extends PlansStrategyAdopter {
  override def run(person: HasPlansAndId[Plan, Person]): Unit = {
    ReplanningUtil.updateAndAddExperiencedPlan(person)

    val plan = new ExpBetaPlanSelector(1).selectPlan(person)
    person.setSelectedPlan(plan)
    // see page 12: http://svn.vsp.tu-berlin.de/repos/public-svn/publications/vspwp/2014/14-20/user-guide-0.6.0-2014-09-12.pdf for choice of 1.
  }
}
