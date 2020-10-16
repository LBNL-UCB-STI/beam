package beam.sim.population

import java.util.Random

import beam.agentsim
import beam.router.Modes.BeamMode
import beam.sim.{BeamScenario, BeamServices}
import beam.utils.plan.sampling.AvailableModeUtils
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.population.{Person, Population}
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.population.PersonUtils
import org.matsim.households.Household

import scala.collection.JavaConverters._

/**
  * An interface that handles setting/updating attributes for the population.
  */
trait PopulationAdjustment extends LazyLogging {
  import PopulationAdjustment._

  val scenario: Scenario
  val beamScenario: BeamScenario

  /**
    * Collects the individual person attributes as [[beam.sim.population.AttributesOfIndividual]] and stores them as a custom attribute "beam-attributes" under the person.
    *
    * @param population The population in the scenario
    * @return updated population
    */
  private def updateAttributes(population: Population): Population = {
    val personHouseholds = scenario.getHouseholds.getHouseholds
      .values()
      .asScala
      .flatMap(h => h.getMemberIds.asScala.map(_ -> h))
      .toMap

    //Iterate over each person in the population
    population.getPersons.asScala.foreach {
      case (_, person) =>
        val attributes = createAttributesOfIndividual(beamScenario, population, person, personHouseholds(person.getId))
        person.getCustomAttributes.put(PopulationAdjustment.BEAM_ATTRIBUTES, attributes)
    }
    population
  }

  /**
    * Updates the population , all individual's attributes and logs the modes
    *
    * @param scenario selected scenario
    * @return updated population
    */
  final def update(scenario: Scenario): Population = {
    val populationWithAttributes = updateAttributes(scenario.getPopulation)
    updatePopulation(scenario)
    logModes(populationWithAttributes)
    populationWithAttributes
  }

  /**
    * Verified if all individuals have the excluded modes attribute and logs the count of each excluded mode.
    *
    * @param population population from the scenario
    */
  protected[population] final def logModes(population: Population): Unit = {
    val persons = population.getPersons.asScala

    val excludedModesPerPerson = persons.keys.toList
      .flatMap { personId =>
        Option(
          population.getPersonAttributes.getAttribute(personId.toString, PopulationAdjustment.EXCLUDED_MODES)
        )
      }

    val allExcludedModes = excludedModesPerPerson
      .map {
        case x: Iterable[_] => x.mkString(",")
        case x: String      => x
      }
      .filter(_.nonEmpty)
      .flatMap(_.split(","))

    if (allExcludedModes.nonEmpty) {
      logger.info(s"Modes excluded:")
      allExcludedModes
        .groupBy(identity)
        .foreach { case (mode, modes) => logger.info(s"$mode -> ${modes.size}") }
    }

    // log error if excluded modes attributes is missing for at least one person in the population
    if (persons.size != excludedModesPerPerson.size) {
      logger.error("Not all agents have person attributes - is attributes file missing ?")
    }
  }

  protected def updatePopulation(scenario: Scenario): Population

  /**
    * Adds the given mode to the list of available modes for the person
    *
    * @param population population from the scenario
    * @param personId the person to whom the above mode needs to be added
    * @param mode mode to be added
    */
  protected def addMode(population: Population, personId: String, mode: String): Population = {
    val person = population.getPersons.get(Id.createPersonId(personId))
    val availableModes = AvailableModeUtils.availableModesForPerson(person)
    if (!availableModes.exists(am => am.value.equalsIgnoreCase(mode))) {
      val newAvailableModes: Seq[String] = availableModes.map(_.value) :+ mode
      AvailableModeUtils.setAvailableModesForPerson(person, population, newAvailableModes)
    }
    population
  }

  /**
    * Checks if the the given mode is available for the person
    *
    * @param population population from the scenario
    * @param personId the person to whom the above mode availability needs to be verified
    * @param modeToCheck mode to be checked
    */
  protected def existsMode(population: Population, personId: String, modeToCheck: String): Boolean = {
    AvailableModeUtils
      .availableModesForPerson(
        population.getPersons
          .get(Id.createPersonId(personId))
      )
      .exists(_.value.equalsIgnoreCase(modeToCheck))
  }

  /**
    * Removes the given mode from the list of available modes for the person
    *
    * @param population population from the scenario
    * @param personId the person to whom the above mode needs to be removed
    * @param modeToRemove mode to be removed
    */
  protected def removeMode(population: Population, personId: String, modeToRemove: String*): Unit = {
    val person = population.getPersons.get(Id.createPersonId(personId))
    val availableModes = AvailableModeUtils.availableModesForPerson(person)
    val newModes: Seq[BeamMode] = availableModes.filterNot(m => modeToRemove.exists(r => r.equalsIgnoreCase(m.value)))
    AvailableModeUtils.replaceAvailableModesForPerson(person, newModes.map(_.value))
  }

  /**
    * Remove the given mode from the list of available modes for all the individuals in the population
    *
    * @param population population from the scenario
    * @param modeToRemove mode to be removed
    */
  protected def removeModeAll(population: Population, modeToRemove: String*): Unit = {
    population.getPersons.keySet().forEach { personId =>
      this.removeMode(population, personId.toString, modeToRemove: _*)
    }
  }

  def assignModeUniformDistribution(population: Population, mode: String, pct: Double): Unit = {
    val rand: Random = new Random(beamScenario.beamConfig.matsim.modules.global.randomSeed)
    val numPop = population.getPersons.size()
    rand.ints(0, numPop).distinct().limit((numPop * pct).toLong).forEach { num =>
      val personId = population.getPersons.keySet().toArray(new Array[Id[Person]](0))(num).toString
      addMode(population, personId, mode)
    }
  }
}

/**
  * A companion object for the PopulationAdjustment Interface
  */
object PopulationAdjustment extends LazyLogging {
  val DEFAULT_ADJUSTMENT = "DEFAULT_ADJUSTMENT"
  val PERCENTAGE_ADJUSTMENT = "PERCENTAGE_ADJUSTMENT"
  val DIFFUSION_POTENTIAL_ADJUSTMENT = "DIFFUSION_POTENTIAL_ADJUSTMENT"
  val EXCLUDE_TRANSIT = "EXCLUDE_TRANSIT"
  val HALF_TRANSIT = "HALF_TRANSIT"
  val EXCLUDED_MODES = "excluded-modes"
  val BEAM_ATTRIBUTES = "beam-attributes"

  /**
    * Generates the population adjustment interface based on the configuration set
    *
    * @param beamServices beam services
    * @return An instance of [[beam.sim.population.PopulationAdjustment]]
    */
  def getPopulationAdjustment(beamServices: BeamServices): PopulationAdjustment = {
    beamServices.beamConfig.beam.agentsim.populationAdjustment match {
      case DEFAULT_ADJUSTMENT =>
        DefaultPopulationAdjustment(beamServices)
      case PERCENTAGE_ADJUSTMENT =>
        PercentagePopulationAdjustment(beamServices)
      case EXCLUDE_TRANSIT =>
        ExcludeAllTransit(beamServices)
      case HALF_TRANSIT =>
        ExcludeHalfTransit(beamServices)
      case DIFFUSION_POTENTIAL_ADJUSTMENT =>
        new DiffusionPotentialPopulationAdjustment(beamServices)
      case adjClass =>
        try {
          Class
            .forName(adjClass)
            .getDeclaredConstructors()(0)
            .newInstance(beamServices)
            .asInstanceOf[PopulationAdjustment]
        } catch {
          case e: Exception =>
            throw new IllegalStateException(s"Unknown PopulationAdjustment: $adjClass", e)
        }
    }
  }

  /**
    * Gets the beam attributes for the given person in the population
    *
    * @param population population from the scenario
    * @param personId the respective person's id
    * @return custom beam attributes as an instance of [[beam.sim.population.AttributesOfIndividual]]
    */
  def getBeamAttributes(population: Population, personId: String): AttributesOfIndividual = {
    population.getPersons
      .get(Id.createPersonId(personId))
      .getCustomAttributes
      .get(BEAM_ATTRIBUTES)
      .asInstanceOf[AttributesOfIndividual]
  }

  def createAttributesOfIndividual(
    beamScenario: BeamScenario,
    population: Population,
    person: Person,
    household: Household
  ): AttributesOfIndividual = {
    val personAttributes = population.getPersonAttributes
    // Read excluded-modes set for the person and calculate the possible available modes for the person
    val excludedModes = AvailableModeUtils.getExcludedModesForPerson(population, person.getId.toString)
    val availableModes: Seq[BeamMode] = BeamMode.allModes.filterNot { mode =>
      excludedModes.exists(em => em.equalsIgnoreCase(mode.value))
    }
    // Read person attribute "income" and default it to 0 if not set
    val income = Option(personAttributes.getAttribute(person.getId.toString, "income"))
      .map(_.asInstanceOf[Double])
      .getOrElse(0D)
    // Read person attribute "modalityStyle"
    val modalityStyle =
      Option(person.getSelectedPlan)
        .map(_.getAttributes)
        .flatMap(attrib => Option(attrib.getAttribute("modality-style")).map(_.toString))

    // Read household attributes for the person
    val householdAttributes = HouseholdAttributes(
      household,
      agentsim.agents.Population.getVehiclesFromHousehold(household, beamScenario)
    )

    // Read person attribute "valueOfTime", use function of HH income if not, and default it to the respective config value if neither is found
    val valueOfTime: Double =
      Option(personAttributes.getAttribute(person.getId.toString, "valueOfTime"))
        .map(_.asInstanceOf[Double])
        .getOrElse(
          incomeToValueOfTime(
            householdAttributes.householdIncome,
            beamScenario.beamConfig.beam.agentsim.agents.modalBehaviors.minimumValueOfTime
          ).getOrElse(beamScenario.beamConfig.beam.agentsim.agents.modalBehaviors.defaultValueOfTime)
        )
    // Generate the AttributesOfIndividual object as save it as custom attribute - "beam-attributes" for the person
    AttributesOfIndividual(
      householdAttributes = householdAttributes,
      modalityStyle = modalityStyle,
      isMale = Option(PersonUtils.getSex(person)).getOrElse("M").equalsIgnoreCase("M"),
      availableModes = availableModes,
      valueOfTime = valueOfTime,
      age = Option(PersonUtils.getAge(person)),
      income = Some(income)
    )
  }

  def incomeToValueOfTime(income: Double, minimumValueOfTime: Double = 7.25): Option[Double] = {
    val workHoursPerYear = 51 * 40 // TODO: Make nonlinear--eg https://ac.els-cdn.com/S0965856411001613/1-s2.0-S0965856411001613-main.pdf
    val wageFactor = 0.5
    if (income > 0) {
      Some(math.max(income / workHoursPerYear * wageFactor, minimumValueOfTime))
    } else {
      None
    }
  }
}
