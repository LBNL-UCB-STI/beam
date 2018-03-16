package beam.replanning

import scala.util.Random

import com.google.inject.Provider
import org.matsim.api.core.v01.population.Plan
import org.matsim.api.core.v01.replanning.PlanStrategyModule
import org.matsim.core.replanning.selectors.RandomPlanSelector
import org.matsim.core.replanning.{PlanStrategy, PlanStrategyImpl, ReplanningContext}
import scala.collection.JavaConverters._

class SwitchModalityStyle extends Provider[PlanStrategy] {
  override def get(): PlanStrategy = {
    new PlanStrategyImpl.Builder(new RandomPlanSelector())
      .addStrategyModule(new PlanStrategyModule {
        override def prepareReplanning(replanningContext: ReplanningContext): Unit = {}
        override def handlePlan(plan: Plan): Unit = {
          val stylesAlreadyTried = plan.getPerson.getPlans.asScala.map(_.getAttributes.getAttribute("modality-style")).distinct.toList
          val allStyles = List("class1", "class2", "class3", "class4", "class5", "class6")
          val styleToTryNext = if(stylesAlreadyTried.size == allStyles.size){
            SwitchModalityStyle.getRandomElement(allStyles, new Random)
          }else{
            SwitchModalityStyle.getRandomElement(allStyles.filter(!stylesAlreadyTried.contains(_)),new Random)
          }
          plan.getAttributes.putAttribute("modality-style",styleToTryNext)
        }
        override def finishReplanning(): Unit = {}
      })
      .build()
  }

}

object SwitchModalityStyle{
  def getRandomElement(list: Seq[String], random: Random): String = list(random.nextInt(list.length))
}
