package beam.replanning

import javax.inject.Inject
import org.matsim.api.core.v01.population.{HasPlansAndId, Person, Plan}
import org.matsim.core.config.Config

import scala.collection.JavaConverters._
import scala.util.Random

class SwitchModalityStyle @Inject()(config: Config) extends PlansStrategyAdopter {
  override def run(person: HasPlansAndId[Plan, Person]): Unit = {
    ReplanningUtil.makeExperiencedMobSimCompatible(person)
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
}

object SwitchModalityStyle {

  def getRandomElement(list: Seq[String], random: Random): String =
    list(random.nextInt(list.length))
}
