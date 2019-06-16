package beam.utils.csv.writers

import beam.sim.population.AttributesOfIndividual
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.households.Household
import org.matsim.utils.objectattributes.ObjectAttributes

import scala.collection.JavaConverters._

object PopulationCsvWriter extends ScenarioCsvWriter {

  override protected val fields: Seq[String] =
    Seq("personId", "age", "isFemale", "householdId", "householdRank", "excludedModes", "valueOfTime")

  // This method is needed because different sources fill differently
  // matsim xml loader fill the age in the property customAttributes
  // urbansim loader fill the age in the property personAttributes
  def readAge(option1: Option[Int], option2: Option[Int]): Option[Int] = {
    (option1, option2) match {
      case (Some(v1), None)     => Some(v1)
      case (Some(v1), Some(v2)) => Some(Integer.max(v1, v2))
      case (None, Some(v2))     => Some(v2)
      case (None, None)         => None
    }
  }

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

      val personAge = readAge(
        customAttributes.age,
        Option(personAttributes.getAttribute(person.getId.toString, "age")).map(_.toString.toInt)
      ).map(_.toString).getOrElse("")

      val values = Seq(
        person.getId.toString,
        personAge,
        !customAttributes.isMale,
        personIdToHouseHoldId.get(person.getId).map(_.toString).getOrElse(""),
        String.valueOf(personAttributes.getAttribute(person.getId.toString, "rank")),
        excludedModes,
        Option(personAttributes.getAttribute(person.getId.toString, "valueOfTime"))
          .getOrElse(customAttributes.valueOfTime)
      )
      values.mkString("", FieldSeparator, LineSeparator)
    }
  }

}
