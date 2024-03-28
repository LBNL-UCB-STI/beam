package beam.utils.csv.writers

import beam.sim.population.AttributesOfIndividual
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.households.Household

import scala.collection.JavaConverters._
import scala.util.Try
import beam.utils.scenario.{HouseholdId, PersonId, PersonInfo}
import ScenarioCsvWriter._
import beam.utils.{OutputDataDescriptor, OutputDataDescriptorObject}
import org.matsim.core.population.PopulationUtils

object PopulationCsvWriter extends ScenarioCsvWriter {

  override protected val fields: Seq[String] =
    Seq(
      "personId",
      "age",
      "isFemale",
      "householdId",
      "householdRank",
      "excludedModes",
      "rideHailServiceSubscription",
      "valueOfTime"
    )

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

    scenario.getPopulation.getPersons.values().asScala.toIterator.map { person =>
      val personId: Id[Person] = person.getId

      val excludedModes: Seq[String] = Option(
        PopulationUtils.getPersonAttribute(person, "excluded-modes")
      ) match {
        case None      => Seq.empty
        case Some(att) => att.toString.split(",").toSeq
      }

      val maybeAttribs: Option[AttributesOfIndividual] =
        Option(person.getCustomAttributes.get("beam-attributes"))
          .map(_.asInstanceOf[AttributesOfIndividual])

      val rideHailServiceSubscription = maybeAttribs.fold(Seq.empty[String])(_.rideHailServiceSubscription)

      val personAge = readAge(
        maybeAttribs.flatMap(_.age),
        Option(PopulationUtils.getPersonAttribute(person, "age")).map(_.toString.toInt)
      ).map(_.toString).getOrElse("")

      val isFemale = {
        val isMale = maybeAttribs
          .map(_.isMale)
          .getOrElse(PopulationUtils.getPersonAttribute(person, "sex") == "F")
        !isMale
      }
      val valueOfTime = Option(PopulationUtils.getPersonAttribute(person, "valueOfTime"))
        .getOrElse(maybeAttribs.map(_.valueOfTime).getOrElse(8.0))

      val rank = String.valueOf(PopulationUtils.getPersonAttribute(person, "rank"))

      val houseHoldId: String = personIdToHouseHoldId.get(personId).map(_.toString).getOrElse("")

      val info = PersonInfo(
        personId = PersonId(personId.toString),
        householdId = HouseholdId(houseHoldId),
        rank = Try(rank.toInt).getOrElse(0),
        age = Try(personAge.toInt).getOrElse(0),
        isFemale = isFemale,
        valueOfTime = Try(valueOfTime.toString.toDouble).getOrElse(0),
        excludedModes = excludedModes,
        rideHailServiceSubscription = rideHailServiceSubscription,
        industry = None
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
    val rideHailServiceSubscription =
      personInfo.rideHailServiceSubscription.mkString(ArrayStartString, ArrayItemSeparator, ArrayEndString)

    val values = Seq(
      personInfo.personId.id,
      personInfo.age,
      personInfo.isFemale,
      personInfo.householdId.id,
      personInfo.rank,
      excludedModes,
      rideHailServiceSubscription,
      personInfo.valueOfTime
    )
    values.mkString("", FieldSeparator, LineSeparator)
  }

  def outputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("PopulationCsvWriter", "population.csv.gz")(
      """
      personId                    | Id of persons that are presented in the simulation
      age                         | Person age
      isFemale                    | Boolean value indicating if the person is female
      householdId                 | Person's household id
      householdRank               | Person rank
      excludedModes               | Modes that are forbidden for the person
      rideHailServiceSubscription | List of ride-hail services that person has subscription to
      valueOfTime                 | Value of time in dollar per hour
        """
    )
}
