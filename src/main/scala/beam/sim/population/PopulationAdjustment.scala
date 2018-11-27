package beam.sim.population

import beam.agentsim
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.router.Modes.BeamMode
import beam.sim.BeamServices
import beam.sim.population.PopulationAdjustment.AVAILABLE_MODES
import beam.utils.plan.sampling.AvailableModeUtils.availableModeParser
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.population.{Population => MPopulation}
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.population.PersonUtils
import org.matsim.utils.objectattributes.ObjectAttributes

import scala.collection.JavaConverters
import scala.util.Try

trait PopulationAdjustment extends LazyLogging {

  val beamServices: BeamServices

  def updateAttributes(population: MPopulation): MPopulation = {
    val personAttributes: ObjectAttributes = population.getPersonAttributes

    JavaConverters
      .mapAsScalaMap(population.getPersons)
      .values
      .map { person =>
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
          ).fold(BeamMode.availableModes)(
            attr => availableModeParser(attr.toString)
          )
          val income = Try { personAttributes.getAttribute(person.getId.toString, "income") } match {
            case scala.util.Success(value)     => Option(value.asInstanceOf[Double])
            case scala.util.Failure(exception) => Some(0.0)
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
      }
    population
  }

  final def update(scenario: Scenario): MPopulation = {
    val result = updatePopulation(scenario)
    logModes(result)
    updateAttributes(result)
  }

  protected final def logModes(population: MPopulation): Unit = {
    import scala.collection.JavaConverters._
    logger.info("Modes' Availability:")

    var allAgentshaveAttributes = true
    population.getPersons
      .keySet()
      .asScala
      .map(
        personId => {
          if (population.getPersonAttributes.getAttribute(personId.toString, AVAILABLE_MODES) != null) {
            population.getPersonAttributes.getAttribute(personId.toString, AVAILABLE_MODES).toString.split(",")
          } else {
            allAgentshaveAttributes = false
            Array[String]()
          }
        }
      )
      .toList
      .flatten
      .groupBy(identity)
      .mapValues(_.size)
      .foreach(t => logger.info(t.toString()))

    if (!allAgentshaveAttributes) {
      logger.error("not all agents have person attributes - is attributes file missing?")
    }
  }

  protected def updatePopulation(scenario: Scenario): MPopulation

  protected def addMode(population: MPopulation, personId: String, mode: String): Unit = {
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
  }

  protected def existsMode(population: MPopulation, personId: String, modeToCheck: String): Boolean = {
    val modes = population.getPersonAttributes.getAttribute(personId, AVAILABLE_MODES).toString
    modes.split(",").contains(modeToCheck)
  }

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
  }

  // remove mode from all attributes
  protected def removeModeAll(population: MPopulation, modeToRemove: String*): Unit = {
    population.getPersons.keySet().forEach { person =>
      val modes = population.getPersonAttributes
        .getAttribute(person.toString, "beam-attributes")
        .asInstanceOf[AttributesOfIndividual]
        .availableModes
        .toString
      modeToRemove.foreach(
        mode =>
          population.getPersonAttributes
            .putAttribute(
              person.toString,
              AVAILABLE_MODES,
              modes.split(",").filterNot(_.equalsIgnoreCase(mode)).mkString(",")
          )
      )
    }
  }
}

object PopulationAdjustment {
  val DEFAULT_ADJUSTMENT = "DEFAULT_ADJUSTMENT"
  val PERCENTAGE_ADJUSTMENT = "PERCENTAGE_ADJUSTMENT"
  val DIFFUSION_POTENTIAL_ADJUSTMENT = "DIFFUSION_POTENTIAL_ADJUSTMENT"
  val AVAILABLE_MODES = "available-modes"

  def getPopulationAdjustment(beamServices: BeamServices): PopulationAdjustment = {
    beamServices.beamConfig.beam.agentsim.populationAdjustment match {
      case DEFAULT_ADJUSTMENT =>
        new DefaultPopulationAdjustment(beamServices)
      case PERCENTAGE_ADJUSTMENT =>
        new PercentagePopulationAdjustment(beamServices)
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
}
