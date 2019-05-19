package beam.utils.csv.writers

import beam.sim.population.AttributesOfIndividual
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.households.Household
import org.matsim.utils.objectattributes.ObjectAttributes

import scala.collection.JavaConverters._

object PopulationCsvWriter extends ScenarioCsvWriter {

  override protected val fields: Seq[String] =
    Seq("personId", "age", "isFemale", "householdId", "houseHoldRank", "excludedModes")

  override def contentIterator(scenario: Scenario): Iterator[String] = {
    val personIdToHouseHoldId: Map[Id[Person], Id[Household]] = scenario.getHouseholds.getHouseholds
      .values()
      .asScala
      .flatMap { h =>
        h.getMemberIds.asScala.map(idPerson => idPerson -> h.getId)
      }
      .toMap

    val personAttributes: ObjectAttributes = scenario.getPopulation.getPersonAttributes

    scenario.getPopulation.getPersons.values().asScala.toIterator.map { person =>
      val customAttributes: AttributesOfIndividual =
        person.getCustomAttributes.get("beam-attributes").asInstanceOf[AttributesOfIndividual]

      // `personAttributes.getAttribute(...)` can return `null`
      val excludedModes = Option(
        personAttributes
          .getAttribute(person.getId.toString, "excluded-modes")
      ).map { attrib =>
          attrib.toString
            .replaceAll(",", ArrayItemSeparator)
            .split(ArrayItemSeparator)
            .mkString(ArrayStartString, ArrayItemSeparator, ArrayEndString)
        }
        .getOrElse("")

      val values = Seq(
        person.getId.toString,
        customAttributes.age.getOrElse(""),
        !customAttributes.isMale,
        personIdToHouseHoldId(person.getId),
        String.valueOf(personAttributes.getAttribute(person.getId.toString, "rank")),
        excludedModes
      )
      values.mkString("", FieldSeparator, LineSeparator)
    }
  }

}
