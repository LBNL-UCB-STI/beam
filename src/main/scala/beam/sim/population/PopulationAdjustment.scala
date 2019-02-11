package beam.sim.population

import beam.agentsim
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.router.Modes.BeamMode
import beam.sim.BeamServices
<<<<<<< HEAD
import beam.sim.population.PopulationAdjustment.AVAILABLE_MODES
import beam.utils.plan.sampling.AvailableModeUtils.availableModeParser
=======
import beam.sim.population.PopulationAdjustment.BEAM_ATTRIBUTES
>>>>>>> eliminate the usage of available-modes attribute outside PopulationAdjustment.scala
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.population.{Population => MPopulation}
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.population.PersonUtils
import org.matsim.utils.objectattributes.ObjectAttributes

import scala.collection.JavaConverters
import scala.util.Try

/**
  * An interface that handles setting/updating attributes for the population.
  */
trait PopulationAdjustment extends LazyLogging {

  val beamServices: BeamServices

  /**
    * Collects the individual person attributes as [[beam.sim.population.AttributesOfIndividual]] and stores them as a custom attribute "beam-attributes" under the person.
    * @param population The population in the scenario
    * @return updated population
    */
  def updateAttributes(population: MPopulation): MPopulation = {
    val personAttributes: ObjectAttributes = population.getPersonAttributes

    JavaConverters
      .mapAsScalaMap(population.getPersons)
      .values
      .map { person =>
<<<<<<< HEAD
        {

          val valueOfTime: Double =
            personAttributes.getAttribute(person.getId.toString, "valueOfTime") match {
              case null =>
                beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.defaultValueOfTime
              case specifiedVot =>
                specifiedVot.asInstanceOf[Double]
            }

          val availableModes: Seq[BeamMode] = Option(
            personAttributes.getAttribute(person.getId.toString, "available-modes")
          ).fold(BeamMode.allBeamModes.seq)(
            attr => availableModeParser(attr.toString)
          )
          val income = Try { personAttributes.getAttribute(person.getId.toString, "income") } match {
            case scala.util.Success(value)     => Option(value.asInstanceOf[Double])
            case scala.util.Failure(exception) => Some(0.0)

=======
        // Read person attribute "valueOfTime" and default it to the respective config value if not found
        val valueOfTime: Double =
          Option(personAttributes.getAttribute(person.getId.toString, "valueOfTime"))
            .map(_.asInstanceOf[Double])
            .getOrElse(beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.defaultValueOfTime)
        // Read excluded-modes set for the person and calculate the possible available modes for the person
        val excludedModes = PopulationAdjustment.getExcludedModes(population, person.getId.toString)
        val availableModes: Seq[BeamMode] = if (excludedModes.isEmpty) {
          BeamMode.allBeamModes
        } else {
          BeamMode.allBeamModes filterNot { mode =>
            excludedModes.exists(em => em.equalsIgnoreCase(mode.toString))
>>>>>>> eliminate the usage of available-modes attribute outside PopulationAdjustment.scala
          }
          val modalityStyle =
            Option(person.getSelectedPlan.getAttributes.getAttribute("modality-style"))
              .map(_.asInstanceOf[String])

          val householdAttributes = beamServices.personHouseholds.get(person.getId).fold(HouseholdAttributes.EMPTY) {
            household =>
              val houseHoldVehicles: Map[Id[BeamVehicle], BeamVehicle] =
                agentsim.agents.Population.getVehiclesFromHousehold(household, beamServices)
              HouseholdAttributes(household, houseHoldVehicles)
          }
          val attributes =
            AttributesOfIndividual(
              householdAttributes,
              modalityStyle,
              Option(PersonUtils.getSex(person)).getOrElse("M").equalsIgnoreCase("M"),
              availableModes,
              valueOfTime,
              Option(PersonUtils.getAge(person)),
              income
            )

          person.getCustomAttributes.put("beam-attributes", attributes)

        }
<<<<<<< HEAD

=======
        // Generate the AttributesOfIndividual object as save it as custom attribute - "beam-attributes" for the person
        val attributes =
          AttributesOfIndividual(
            householdAttributes,
            modalityStyle,
            Option(PersonUtils.getSex(person)).getOrElse("M").equalsIgnoreCase("M"),
            availableModes,
            valueOfTime,
            Option(PersonUtils.getAge(person)),
            Some(income)
          )
        person.getCustomAttributes.put(PopulationAdjustment.BEAM_ATTRIBUTES, attributes)
>>>>>>> eliminate the usage of available-modes attribute outside PopulationAdjustment.scala
      }
    population
  }

  /**
    * Updates the population , all individual's attributes and logs the modes
    * @param scenario selected scenario
    * @return updated population
    */
  final def update(scenario: Scenario): MPopulation = {
    val result = updatePopulation(scenario)
    logModes(result)
    updateAttributes(result)
  }

  /**
    * Verified if all individuals have the excluded modes attribute and logs the count of each excluded mode.
    * @param population population from the scenario
    */
  protected final def logModes(population: MPopulation): Unit = {

    logger.info("Modes excluded:")

    // initialize all excluded modes to empty array
    var allExcludedModes: Array[String] = Array.empty

// check if excluded modes is defined for all individuals
    val allAgentsHaveAttributes = population.getPersons.asScala.forall { entry =>
      val personExcludedModes = Option(
        population.getPersonAttributes.getAttribute(entry._1.toString, PopulationAdjustment.EXCLUDED_MODES)
      ).map(_.toString)
      // if excluded modes is defined for the person add it to the cumulative list
      if (personExcludedModes.isDefined && personExcludedModes.get.nonEmpty)
        allExcludedModes = allExcludedModes ++ personExcludedModes.get.split(",")
      personExcludedModes.isDefined
    }
    // count the number of excluded modes for each mode type
    allExcludedModes
      .groupBy(x => x)
      .foreach(t => logger.info(s"${t._1} -> ${t._2.length}"))

    // log error if excluded modes attributes is missing for at least one person in the population
    if (!allAgentsHaveAttributes) {
      logger.error("Not all agents have person attributes - is attributes file missing ?")
    }
  }

  protected def updatePopulation(scenario: Scenario): MPopulation

  /**
    * Adds the given mode to the list of available modes for the person
    * @param population population from the scenario
    * @param personId the person to whom the above mode needs to be added
    * @param mode mode to be added
    */
<<<<<<< HEAD
  protected def addMode(population: MPopulation, personId: String, mode: String): Unit = {
    val personAttributes = population.getPersonAttributes
    val excludedModes =
      Option(personAttributes.getAttribute(personId, PopulationAdjustment.EXCLUDED_MODES))
        .map(_.asInstanceOf[String].trim)
        .getOrElse("")
    val modes = population.getPersonAttributes
      .getAttribute(personId, "beam-attributes")
      .asInstanceOf[AttributesOfIndividual]
      .availableModes
      .toString
    if (!existsMode(population, personId, mode)) {
      population.getPersonAttributes
        .putAttribute(
          personId,
          AVAILABLE_MODES,
          s"$modes,$mode"
        )
    }
=======
  protected def addMode(population: MPopulation, personId: String, mode: String): MPopulation = {
    val attributesOfIndividual = PopulationAdjustment.getBeamAttributes(population, personId)
    val availableModes = attributesOfIndividual.availableModes
      .map(_.toString.toLowerCase)
    if (!availableModes.contains(mode)) {
      val newAvailableModes: Seq[String] = availableModes :+ mode
      val attributesOfIndividual = PopulationAdjustment
        .adjustBeamAttributes(population, personId.toString, newAvailableModes)
      population.getPersons
        .get(personId)
        .getCustomAttributes
        .put(BEAM_ATTRIBUTES, attributesOfIndividual)
    }
    population
>>>>>>> eliminate the usage of available-modes attribute outside PopulationAdjustment.scala
  }

  /**
    * Checks if the the given mode is available for the person
    * @param population population from the scenario
    * @param personId the person to whom the above mode availability needs to be verified
    * @param modeToCheck mode to be checked
    */
  protected def existsMode(population: MPopulation, personId: String, modeToCheck: String): Boolean = {
    val modes = population.getPersonAttributes.getAttribute(personId, AVAILABLE_MODES).toString
    modes.split(",").contains(modeToCheck)
  }

  /**
    * Removes the given mode from the list of available modes for the person
    * @param population population from the scenario
    * @param personId the person to whom the above mode needs to be removed
    * @param modeToRemove mode to be removed
    */
<<<<<<< HEAD
  protected def removeMode(population: MPopulation, personId: String, modeToRemove: String*): Unit = {

    val modes = population.getPersonAttributes
      .getAttribute(personId, "beam-attributes")
      .asInstanceOf[AttributesOfIndividual]
      .availableModes
      .toString
    modeToRemove.foreach(
      mode =>
        population.getPersonAttributes
          .putAttribute(
            personId,
            AVAILABLE_MODES,
            modes.split(",").filterNot(_.equalsIgnoreCase(mode)).mkString(",")
        )
    )
=======
  protected def removeMode(population: MPopulation, personId: String, modeToRemove: String*): MPopulation = {
    val availableModes = PopulationAdjustment.getAvailableModes(population, personId)
    val newModes: Seq[String] = availableModes.filterNot(m => modeToRemove.exists(r => r.equalsIgnoreCase(m)))
    val attributesOfIndividual = PopulationAdjustment
      .adjustBeamAttributes(population, personId, newModes)
    population.getPersons
      .get(personId)
      .getCustomAttributes
      .put(BEAM_ATTRIBUTES, attributesOfIndividual)
    population
>>>>>>> eliminate the usage of available-modes attribute outside PopulationAdjustment.scala
  }

  /**
    * Remove the given mode from the list of available modes for all the individuals in the population
    * @param population population from the scenario
    * @param modeToRemove mode to be removed
    */
  protected def removeModeAll(population: MPopulation, modeToRemove: String*): Unit = {
    population.getPersons.keySet() forEach { person =>
      this.removeMode(population, person.toString, modeToRemove: _*)
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
  val AVAILABLE_MODES = "available-modes"

  /**
    * Generates the population adjustment interface based on the configuration set
    * @param beamServices beam services
    * @return An instance of [[beam.sim.population.PopulationAdjustment]]
    */
  def getPopulationAdjustment(beamServices: BeamServices): PopulationAdjustment = {
    beamServices.beamConfig.beam.agentsim.populationAdjustment match {
      case DEFAULT_ADJUSTMENT =>
        DefaultPopulationAdjustment(beamServices)
      case PERCENTAGE_ADJUSTMENT =>
        PercentagePopulationAdjustment(beamServices)
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

<<<<<<< HEAD
=======
  /**
    * Gets the beam attributes for the given person in the population
    * @param population population from the scenario
    * @param personId the respective person's id
    * @return custom beam attributes as an instance of [[beam.sim.population.AttributesOfIndividual]]
    */
  def getBeamAttributes(population: MPopulation, personId: String): AttributesOfIndividual = {
    population.getPersons
      .get(Id.createPersonId(personId))
      .getCustomAttributes
      .get(BEAM_ATTRIBUTES)
      .asInstanceOf[AttributesOfIndividual]
  }

  /**
    * Gets the excluded modes set for the given person in the population
    * @param population population from the scenario
    * @param personId the respective person's id
    * @return List of excluded mode string
    */
  def getExcludedModes(population: MPopulation, personId: String): Array[String] = {
    Option(
      population.getPersonAttributes.getAttribute(personId, PopulationAdjustment.EXCLUDED_MODES)
    ).map(_.toString) match {
      case Some(modes) =>
        if (modes.isEmpty) {
          Array.empty[String]
        } else {
          modes.split(",")
        }
      case None => Array.empty[String]
    }
  }

  /**
    * Gets the available modes for the given person in the population
    * @param population population from the scenario
    * @param personId the respective person's id
    * @return List of available mode string
    */
  def getAvailableModes(population: MPopulation, personId: String): Seq[String] = {
    getBeamAttributes(population, personId).availableModes
      .map(_.toString.toLowerCase)
  }

  /**
    * Sets the available modes for the given person in the population
    * @param population population from the scenario
    * @param personId the respective person's id
    * @param permissibleModes List of permissible modes for the person
    */
  def adjustBeamAttributes(
    population: MPopulation,
    personId: String,
    permissibleModes: Seq[String]
  ): AttributesOfIndividual = {
    val attributesOfIndividual = getBeamAttributes(population, personId)
    val excludedModes = getExcludedModes(population, personId)
    val availableModes = if (excludedModes.nonEmpty) {
      permissibleModes.filterNot(am => excludedModes.exists(em => em.equalsIgnoreCase(am)))
    } else {
      permissibleModes
    }
    try {
      attributesOfIndividual.copy(availableModes = availableModes.map(f => BeamMode.withValue(f.toLowerCase)))
    } catch {
      case e: Exception =>
        logger.error("Error while converting available mode string to respective Beam Mode Enums : " + e.getMessage, e)
        attributesOfIndividual
    }
  }
>>>>>>> eliminate the usage of available-modes attribute outside PopulationAdjustment.scala
}
