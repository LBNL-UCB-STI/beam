package beam.utils.csv.writers

import beam.sim.population.AttributesOfIndividual
import org.matsim.api.core.v01.Scenario
import org.matsim.utils.objectattributes.ObjectAttributes

import scala.collection.JavaConverters._

object PopulationCsvWriter extends ScenarioCsvWriter {

  override protected val fields: Seq[String] =
    Seq("personId", "age", "isFemale", "householdId", "houseHoldRank", "excludedModes")

  override def contentIterator(scenario: Scenario): Iterator[String] = {
    val personAttrib: ObjectAttributes = scenario.getPopulation.getPersonAttributes

    scenario.getPopulation.getPersons.values().asScala.toIterator.map { person =>
      val customAttributes: AttributesOfIndividual =
        person.getCustomAttributes.get("beam-attributes").asInstanceOf[AttributesOfIndividual]
      val values = Seq(
        person.getId.toString,
        customAttributes.age.getOrElse(""),
        !customAttributes.isMale,
        customAttributes.householdAttributes.householdId,
        personAttrib.getAttribute(person.getId.toString, "rank"),
        personAttrib.getAttribute(person.getId.toString, "excluded-modes")
      )
      values.mkString("", FieldSeparator, LineSeparator)
    }
  }

}
