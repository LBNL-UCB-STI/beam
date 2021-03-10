package beam.utils.csv.writers

import java.io.File

import beam.sim.population.AttributesOfIndividual
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.households.Household
import org.matsim.utils.objectattributes.ObjectAttributes
import scala.collection.JavaConverters._
import scala.util.Try

import beam.utils.scenario.{HouseholdId, PersonId, PersonInfo}
import ScenarioCsvWriter._

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
      val personId: Id[Person] = person.getId

      val excludedModes: Seq[String] = Option(
        personAttributes
          .getAttribute(personId.toString, "excluded-modes")
      ) match {
        case None      => Seq.empty
        case Some(att) => att.toString.split(",").toSeq
      }

      val maybeAttribs: Option[AttributesOfIndividual] =
        Option(person.getCustomAttributes.get("beam-attributes"))
          .map(_.asInstanceOf[AttributesOfIndividual])

      val personAge = readAge(
        maybeAttribs.flatMap(_.age),
        Option(personAttributes.getAttribute(personId.toString, "age")).map(_.toString.toInt)
      ).map(_.toString).getOrElse("")

      val isFemale = {
        val isMale = maybeAttribs
          .map(_.isMale)
          .getOrElse(personAttributes.getAttribute(personId.toString, "sex") == "F")
        !isMale
      }
      val valueOfTime = Option(personAttributes.getAttribute(personId.toString, "valueOfTime"))
        .getOrElse(maybeAttribs.map(_.valueOfTime).getOrElse(8.0))

      val rank = String.valueOf(personAttributes.getAttribute(personId.toString, "rank"))

      val houseHoldId: String = personIdToHouseHoldId.get(personId).map(_.toString).getOrElse("")

      val info = PersonInfo(
        personId = PersonId(personId.toString),
        householdId = HouseholdId(houseHoldId),
        rank = Try(rank.toInt).getOrElse(0),
        age = Try(personAge.toInt).getOrElse(0),
        isFemale = isFemale,
        valueOfTime = Try(valueOfTime.toString.toDouble).getOrElse(0),
        excludedModes = excludedModes
      )
      toLine(info)
    }
  }

  override def contentIterator[A](elements: Iterator[A]): Iterator[String] = {
    elements.flatMap {
      case e: PersonInfo => Some(toLine(e))
      case _             => None
    }
  }

  private def toLine(personInfo: PersonInfo): String = {
    val excludedModes = {
      val excludedModesBeforeConvert = personInfo.excludedModes.mkString(",")
      excludedModesBeforeConvert
        .replaceAll(",", ArrayItemSeparator)
        .split(ArrayItemSeparator)
        .mkString(ArrayStartString, ArrayItemSeparator, ArrayEndString)
    }
    val values = Seq(
      personInfo.personId.id,
      personInfo.age,
      personInfo.isFemale,
      personInfo.householdId.id,
      personInfo.rank,
      excludedModes,
      personInfo.valueOfTime
    )
    values.mkString("", FieldSeparator, LineSeparator)
  }
}
