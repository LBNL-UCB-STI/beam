package beam.utils

import beam.sim.BeamServices
import com.typesafe.scalalogging.LazyLogging
import org.matsim.core.scenario.MutableScenario

class ScenarioReaderCsv2(var scenario: MutableScenario, var beamServices: BeamServices, val delimiter: String = ",")
  extends LazyLogging {

  val scenarioFolder = beamServices.beamConfig.beam.agentsim.agents.population.beamPopulationDirectory

  private val defaultAvailableModes =
    "car,ride_hail,bike,bus,funicular,gondola,cable_car,ferry,tram,transit,rail,subway,tram"

  val buildingFilePath = beamServices.beamConfig.beam.inputDirectory + scenarioFolder + "/buildings.csv"
  val personFilePath = beamServices.beamConfig.beam.inputDirectory + scenarioFolder + "/persons.csv"
  val householdFilePath = beamServices.beamConfig.beam.inputDirectory + scenarioFolder + "/households.csv"

  val planFilePath = beamServices.beamConfig.beam.inputDirectory + scenarioFolder + "/plans.csv"
  val unitFilePath = beamServices.beamConfig.beam.inputDirectory + scenarioFolder + "/units.csv"
  val parcelAttrFilePath = beamServices.beamConfig.beam.inputDirectory + scenarioFolder + "/parcel_attr.csv"

  def loadScenario() = {

    scenario.getPopulation.getPersons.clear()
    scenario.getPopulation.getPersonAttributes.clear()
    scenario.getHouseholds.getHouseholds.clear()
    scenario.getHouseholds.getHouseholdAttributes.clear()
    beamServices.privateVehicles.clear()
    /////

    logger.info("Reading units...")
    val units = BeamServices.readUnitsFile(unitFilePath)

    logger.info("Reading parcel attrs")
    val parcelAttrs = BeamServices.readParcelAttrFile(parcelAttrFilePath)

    logger.info("Reading Buildings...")
    val buildings = BeamServices.readBuildingsFile(buildingFilePath)

    logger.info("Reading Persons...")
    val persons = BeamServices.readPersonsFile(personFilePath, scenario.getPopulation, defaultAvailableModes)

    logger.info("Reading plans...")
    val plans = BeamServices.readPlansFile(planFilePath, scenario.getPopulation)

    logger.info("Reading Households...")

    val houseHolds = BeamServices.readHouseHoldsFile(
      householdFilePath,
      scenario,
      beamServices,
      persons.par,
      units.par,
      buildings.par,
      parcelAttrs.par
    )
    /*val houseHolds = BeamServices.readHouseHoldsFile(householdFilePath, scenario, beamServices,
      TrieMap[Id[Household], ListBuffer[Id[Person]]](),
      TrieMap[String, java.util.Map[String, String]](), TrieMap[String, java.util.Map[String, String]](), TrieMap[String, java.util.Map[String, String]]())*/

  }

}
