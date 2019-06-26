package beam.sim

import beam.replanning.SwitchModalityStyle
import javax.inject.Inject
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.api.core.v01.population.{Activity, Person, Plan}
import org.matsim.core.controler.PrepareForSim
import org.matsim.households.Household
import org.matsim.vehicles.Vehicle

import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConverters
import scala.util.Random

class BeamPrepareForSim @Inject()(scenario: Scenario) extends PrepareForSim {

  override def run(): Unit = {
//    keepOnlyActivities()
    assignInitialModalityStyles()

  }

  private def keepOnlyActivities(): Unit = {
    scenario.getPopulation.getPersons
      .values()
      .forEach(person => {
        val cleanedPlans: ArrayBuffer[Plan] = ArrayBuffer()
        person.getPlans.forEach(plan => {
          val cleanedPlan = scenario.getPopulation.getFactory.createPlan()
          plan.getPlanElements.forEach {
            case activity: Activity =>
              cleanedPlan.addActivity(activity)
            case _ => // don't care for legs just now
          }
          cleanedPlan.setScore(null)
          cleanedPlans += cleanedPlan
        })
        person.setSelectedPlan(null)
        person.getPlans.clear()
        cleanedPlans.foreach(person.addPlan)
      })
  }

  def assignInitialModalityStyles(): Unit = {
    val allStyles = List("class1", "class2", "class3", "class4", "class5", "class6")
    val rand = new Random() // should this Random use a fixed seed from beamConfig ?
    scenario.getPopulation.getPersons
      .values()
      .forEach(person => {
        person.getPlans.forEach(plan => {
          plan.getAttributes
            .putAttribute("modality-style", SwitchModalityStyle.getRandomElement(allStyles, rand))
        })
      })
  }

}
