package beam.sim

import beam.agentsim.agents.vehicles.BeamVehicleType.BicycleVehicle
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
    keepOnlyActivities()
    assignInitialModalityStyles()

    // Add bikes
    bicyclePrepareForSim()
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

  /**
    * Utility method preparing BEAM to add bicycles as part of mobsim
    */
  private def bicyclePrepareForSim(): Unit = {
    // Add the bicycle as a vehicle type here
    scenario.getVehicles.addVehicleType(BicycleVehicle.MatsimVehicleType)

    // Add bicycles to household (all for now)
    JavaConverters
      .collectionAsScalaIterable(scenario.getHouseholds.getHouseholds.values())
      .seq
      .foreach {
        addBicycleVehicleIdsToHousehold
      }
  }

  private def addBicycleVehicleIdsToHousehold(household: Household): Unit = {
    val householdMembers: Iterable[Id[Person]] =
      JavaConverters.collectionAsScalaIterable(household.getMemberIds)

    householdMembers.foreach { id: Id[Person] =>
      val bicycleId: Id[Vehicle] = BicycleVehicle.createId(id)
      household.getVehicleIds.add(bicycleId)

      scenario.getVehicles.addVehicle(BicycleVehicle.createMatsimVehicle(bicycleId))
    }
  }

  def assignInitialModalityStyles(): Unit = {
    val allStyles = List("class1", "class2", "class3", "class4", "class5", "class6")
    val rand = new Random()
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
