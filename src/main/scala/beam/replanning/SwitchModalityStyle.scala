package beam.replanning

import com.google.inject.Provider
import org.matsim.api.core.v01.population.Plan
import org.matsim.api.core.v01.replanning.PlanStrategyModule
import org.matsim.core.replanning.selectors.RandomPlanSelector
import org.matsim.core.replanning.{PlanStrategy, PlanStrategyImpl, ReplanningContext}

import scala.collection.JavaConverters._
import scala.util.Random

import javax.inject.Inject

import org.matsim.api.core.v01.population.{HasPlansAndId, Leg, Person, Plan}
import org.matsim.core.config.Config
import org.matsim.core.replanning.{PlanStrategy, ReplanningContext}

import scala.collection.JavaConverters._

import scala.util.Random

class SwitchModalityStyle @Inject()(config: Config) extends PlanStrategy {
  override def init(replanningContext: ReplanningContext): Unit = {}

  override def run(person: HasPlansAndId[Plan, Person]): Unit = {
    ReplanningUtil.updateAndAddExperiencedPlan(person)
    ReplanningUtil.copyRandomPlanAndSelectForMutation(person.getSelectedPlan.getPerson)

    val plan = person.getSelectedPlan

    val stylesAlreadyTried = plan.getPerson.getPlans.asScala
      .map(_.getAttributes.getAttribute("modality-style"))
      .distinct
      .toList
    val allStyles = List("class1", "class2", "class3", "class4", "class5", "class6")
    val styleToTryNext = if (stylesAlreadyTried.size == allStyles.size) {
      SwitchModalityStyle.getRandomElement(allStyles, new Random)
    } else {
      SwitchModalityStyle
        .getRandomElement(allStyles.filter(!stylesAlreadyTried.contains(_)), new Random)
    }
    plan.getAttributes.putAttribute("modality-style", styleToTryNext)
  }

  override def finish(): Unit = {}
}

object SwitchModalityStyle {

  def getRandomElement(list: Seq[String], random: Random): String =
    list(random.nextInt(list.length))
}
