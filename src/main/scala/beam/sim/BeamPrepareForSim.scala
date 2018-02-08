package beam.sim

import javax.inject.Inject

import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.population.{Activity, Plan}
import org.matsim.core.controler.PrepareForSim

class BeamPrepareForSim @Inject()(scenario: Scenario) extends PrepareForSim {

  override def run(): Unit = {
    keepOnlyActivities()
    assignInitialModalityStyles()
  }

  private def keepOnlyActivities(): Unit = {
    scenario.getPopulation.getPersons.values().forEach(person => {
      var cleanedPlans: Vector[Plan] = Vector()
      person.getPlans.forEach(plan => {
        val cleanedPlan = scenario.getPopulation.getFactory.createPlan()
        plan.getPlanElements.forEach {
          case activity: Activity =>
            cleanedPlan.addActivity(activity)
          case _ => // don't care for legs just now
        }
        cleanedPlan.setScore(null)
        cleanedPlans = cleanedPlans :+ cleanedPlan
      })
      person.setSelectedPlan(null)
      person.getPlans.clear()
      cleanedPlans.foreach(person.addPlan)
    })
  }

  def assignInitialModalityStyles(): Unit = {
    scenario.getPopulation.getPersons.values().forEach(person => {
      person.getPlans.forEach(plan => {
        plan.getCustomAttributes.put("modality-style", "class1")
      })
    })
  }

}
