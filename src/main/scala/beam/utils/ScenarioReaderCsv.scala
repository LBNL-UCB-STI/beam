package beam.utils

import beam.agentsim.agents.vehicles.BeamVehicle
import beam.router.Modes.BeamMode
import beam.sim.BeamServices
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.core.population.PopulationUtils
import org.matsim.core.scenario.MutableScenario
import org.matsim.households.{Household, HouseholdIncomeComparator}

import scala.collection.mutable.ListBuffer

class ScenarioReaderCsv(var scenario: MutableScenario, var beamServices: BeamServices, val delimiter: String = ",")
  extends LazyLogging {

  val scenarioFolder = beamServices.beamConfig.beam.agentsim.agents.population.beamPopulationDirectory

  val buildingFilePath = scenarioFolder + "/buildings.csv"
  val personFilePath = scenarioFolder + "/persons.csv"
  val householdFilePath = scenarioFolder + "/households.csv"

  val planFilePath = scenarioFolder + "/plans.csv"
  val unitFilePath = scenarioFolder + "/units.csv"
  val parcelAttrFilePath = scenarioFolder + "/parcel_attr.csv"

  def loadScenario() = {

    scenario.getPopulation.getPersons.clear()
    scenario.getPopulation.getPersonAttributes.clear()
    scenario.getHouseholds.getHouseholds.clear()
    scenario.getHouseholds.getHouseholdAttributes.clear()

    beamServices.vehicles.clear()
    beamServices.privateVehicles.clear()
    /////

    logger.info("Reading units...")
    val units = BeamServices.readUnitsFile(unitFilePath)

    logger.info("Reading parcel attrs")
    val parcelAttrs = BeamServices.readParcelAttrFile(parcelAttrFilePath)

    logger.info("Reading Buildings...")
    val buildings = BeamServices.readBuildingsFile(buildingFilePath)

    logger.info("Reading Persons...")
    val householdPersons = BeamServices.readPersonsFile(personFilePath, scenario.getPopulation, BeamMode.allBeamModes.map(_.value).mkString(","))

    logger.info("Reading plans...")
    BeamServices.readPlansFile(planFilePath, scenario.getPopulation, beamServices)

    logger.info("Total persons loaded {}", scenario.getPopulation.getPersons.size())
    logger.info("Checking persons without selected plan...")

    val listOfPersonsWithoutPlan: ListBuffer[Id[Person]] = ListBuffer()
    scenario.getPopulation.getPersons.forEach {
      case (pk: Id[Person], pv: Person) if (pv.getSelectedPlan == null) => listOfPersonsWithoutPlan += pk
      case _ =>
    }

    logger.info("Removing persons without plan {}", listOfPersonsWithoutPlan.size)
    listOfPersonsWithoutPlan.foreach { p =>
      val _hId = scenario.getPopulation.getPersonAttributes.getAttribute(p.toString, "householdId")

      scenario.getPopulation.getPersonAttributes.removeAllAttributes(p.toString)

      scenario.getPopulation.removePerson(p)

      val hId = Id.create(_hId.toString, classOf[Household])
      householdPersons.get(hId) match {
        case Some(persons: ListBuffer[Id[Person]]) =>
          persons -= p
        case None =>
      }
    }

    logger.info("Reading Households...")
    BeamServices.readHouseholdsFile(
      householdFilePath,
      scenario,
      beamServices,
      householdPersons.par,
      units.par,
      buildings.par,
      parcelAttrs.par
    )

    logger.info("Total households loaded {}", scenario.getHouseholds.getHouseholds.size())
    logger.info("Checking households without members...")
    val listOfHouseholdsWithoutMembers: ListBuffer[Household] = ListBuffer()
    scenario.getHouseholds.getHouseholds.forEach {
      case (hId: Id[Household], h: Household) if (h.getMemberIds.size() == 0) => listOfHouseholdsWithoutMembers += h
      case _ =>
    }

    logger.info("Removing households without members {}", listOfHouseholdsWithoutMembers.size)
    listOfHouseholdsWithoutMembers.foreach { h =>
      removeHouseholdVehicles(h)
      scenario.getHouseholds.getHouseholdAttributes.removeAllAttributes(h.getId.toString)
      scenario.getHouseholds.getHouseholds.remove(h.getId)
    }

    logger.info("The scenario loading is completed..")
  }

  def removeHouseholdVehicles(household: Household) = {
    household.getVehicleIds.forEach(
      vehicleId => beamServices.privateVehicles.remove(Id.create(vehicleId.toString, classOf[BeamVehicle]))
    )
  }
}
