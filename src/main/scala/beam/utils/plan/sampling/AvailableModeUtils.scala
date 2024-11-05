package beam.utils.plan.sampling

import java.util
import scala.collection.JavaConverters
import beam.router.Modes.BeamMode
import beam.sim.BeamScenario
import beam.sim.population.{AttributesOfIndividual, PopulationAdjustment}
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.lang3.StringUtils.isBlank
import org.matsim.api.core.v01.population.{Person, Plan, Population}
import org.matsim.core.population.PopulationUtils
import org.matsim.core.population.algorithms.PermissibleModesCalculator
import org.matsim.households.Household

/**
  * Several utility/convenience methods for mode availability. Note that the MATSim convention
  * is to call these permissible modes. BEAM uses available modes. The semantics are identical.
  */
object AvailableModeUtils extends LazyLogging {

  object AllowAllModes extends PermissibleModesCalculator {

    override def getPermissibleModes(plan: Plan): util.Collection[String] = {
      JavaConverters.asJavaCollection(BeamMode.allModes.map(_.toString))
    }
  }

  def availableModeParser(availableModes: String): Seq[BeamMode] = {
    availableModes.split(",").toSeq map BeamMode.withValue
  }

  /**
    * Gets the excluded modes set for the given person in the population
    *
    * @param person the respective person
    * @return List of excluded mode string
    */
  def getExcludedModesForPerson(person: Person): Array[String] = {
    getAttributeAsArrayOfStrings(person, PopulationAdjustment.EXCLUDED_MODES)
  }

  def getAttributeAsArrayOfStrings(person: Person, attributeName: String): Array[String] = {
    val attributeValueOpt = Option(
      PopulationUtils.getPersonAttribute(person, attributeName)
    )
    attributeValueOpt match {
      case Some(modes: Array[String]) => modes.map(_.trim).filterNot(isBlank)
      case Some(modes: Iterable[_])   => modes.flatMap(_.toString.split(',')).map(_.trim).filterNot(isBlank).toArray
      case Some(modes)                => modes.toString.split(',').map(_.trim).filterNot(isBlank)
      case _                          => Array.empty[String]
    }
  }

  /**
    * Gets the excluded modes set for the given person
    *
    * @param person the respective person
    * @return
    */
  def availableModesForPerson(person: Person): Seq[BeamMode] = {
    getPersonCustomAttributes(person).map(_.availableModes).getOrElse(Seq.empty)
  }

  def availableModesForPerson(person: Person, excludedModes: Set[BeamMode]): Seq[BeamMode] = {
    getPersonCustomAttributes(person).map(_.availableModes).getOrElse(Seq.empty).filterNot(excludedModes.contains)
  }

  /**
    * Sets the available modes for the given person in the population
    *
    * @param person the respective person
    * @param permissibleModes List of permissible modes for the person
    */
  def setAvailableModesForPerson(person: Person, permissibleModes: Seq[String]): Unit = {
    getPersonCustomAttributes(person) match {
      case Some(attributesOfIndividual) =>
        setModesForPerson(person, permissibleModes, attributesOfIndividual)
      case _ =>
        logger.warn(s"Not found attributes of the individual: [$person]")
    }
  }

  private def setModesForPerson(
    person: Person,
    permissibleModes: Seq[String],
    attributesOfIndividual: AttributesOfIndividual
  ): Unit = {
    val availableModes = getAvailableModesOfPerson(person, permissibleModes)
    try {
      val attributesUpdated =
        attributesOfIndividual.copy(availableModes = availableModes.map(f => BeamMode.withValue(f.toLowerCase)))
      person.getCustomAttributes.put(PopulationAdjustment.BEAM_ATTRIBUTES, attributesUpdated)
    } catch {
      case e: Exception =>
        logger.error("Error while converting available mode string to respective Beam Mode Enums : " + e.getMessage, e)
    }
  }

  private def getAvailableModesOfPerson(
    person: Person,
    permissibleModes: Seq[String]
  ): Seq[String] = {
    val excludedModes = getExcludedModesForPerson(person)
    permissibleModes.filterNot(am => excludedModes.exists(em => em.equalsIgnoreCase(am)))
  }

  def setAvailableModesForPerson_v2(
    beamScenario: BeamScenario,
    person: Person,
    household: Household,
    permissibleModes: Seq[String]
  ): Unit = {
    val attributesOfIndividual = getOrElseUpdateAttributesOfIndividual(beamScenario, person, household)
    setModesForPerson(person, permissibleModes, attributesOfIndividual)
  }

  private def getOrElseUpdateAttributesOfIndividual(
    beamScenario: BeamScenario,
    person: Person,
    household: Household
  ): AttributesOfIndividual = {
    getPersonCustomAttributes(person).getOrElse {
      val attributes: AttributesOfIndividual =
        PopulationAdjustment.createAttributesOfIndividual(beamScenario, person, household)
      person.getCustomAttributes.put(PopulationAdjustment.BEAM_ATTRIBUTES, attributes)
      attributes
    }
  }

  private def getPersonCustomAttributes(person: Person): Option[AttributesOfIndividual] = {
    val attributes = person.getCustomAttributes
      .get(PopulationAdjustment.BEAM_ATTRIBUTES)
    Option(attributes.asInstanceOf[AttributesOfIndividual])
  }

  /**
    * Replaces the available modes given with the existing available modes for the given person
    *
    * @param person the respective person
    * @param newAvailableModes List of new available modes to replace
    */
  def replaceAvailableModesForPerson(person: Person, newAvailableModes: Seq[String]): Unit = {
    val maybeIndividual = getPersonCustomAttributes(person)
    if (maybeIndividual.isDefined) {
      val attributesOfIndividual = maybeIndividual.get
      val modes: Seq[BeamMode] = newAvailableModes.flatMap(f => selectBeamMode(f.toLowerCase))
      person.getCustomAttributes.put(
        PopulationAdjustment.BEAM_ATTRIBUTES,
        attributesOfIndividual.copy(availableModes = modes)
      )
    }
  }

  private def selectBeamMode(mode: String): Option[BeamMode] = {
    BeamMode.withValueOpt(mode) match {
      case result @ Some(_) => result
      case None =>
        logger.error(s"Error while converting available mode string [$mode] to respective Beam Mode Enums")
        None
    }
  }

}
