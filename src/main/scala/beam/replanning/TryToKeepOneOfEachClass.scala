package beam.replanning

import beam.sim.MapStringDouble
import org.matsim.api.core.v01.population.{HasPlansAndId, Person, Plan}
import org.matsim.core.replanning.selectors.PlanSelector
import scala.language.existentials

import scala.collection.JavaConverters._

class TryToKeepOneOfEachClass extends PlanSelector[Plan, Person] {
  override def selectPlan(member: HasPlansAndId[Plan, Person]): Plan = {

    val (someClassWithMoreThanOnePlan, thosePlans) = scala.util.Random
      .shuffle(
        member.getPlans.asScala
          .groupBy(plan => plan.getAttributes.getAttribute("modality-style").toString)
          .filter(e => e._2.size > 1)
      )
      .head

    val worstPlanOfThatClassWithRespectToThatClass = thosePlans
      .map(
        plan =>
          (
            plan,
            plan.getAttributes
              .getAttribute("scores")
              .asInstanceOf[MapStringDouble]
              .data(someClassWithMoreThanOnePlan)
        )
      )
      .minBy(_._2)
      ._1

    worstPlanOfThatClassWithRespectToThatClass
  }
}
