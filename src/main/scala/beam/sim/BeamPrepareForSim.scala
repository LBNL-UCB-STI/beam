package beam.sim

import beam.replanning.SwitchModalityStyle
import javax.inject.Inject
import org.matsim.api.core.v01.Scenario
import org.matsim.core.controler.PrepareForSim

import scala.util.Random

class BeamPrepareForSim @Inject() (scenario: Scenario) extends PrepareForSim {

  override def run(): Unit = {
    assignInitialModalityStyles()
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
