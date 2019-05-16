package beam.utils.scenario

import java.io.FileWriter

import beam.utils.FileUtils
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.api.core.v01.population.{Activity, Leg, Person, PlanElement => MatsimPlanElement}
import org.matsim.core.scenario.MutableScenario
import org.matsim.households.Household
import org.supercsv.io.CsvMapWriter
import org.supercsv.prefs.CsvPreference

import scala.collection.JavaConverters._

trait ScenarioWriter {
  def write(scenario: MutableScenario, path: String): Unit
}

object CsvScenarioWriter extends ScenarioWriter with LazyLogging {

  override def write(scenario: MutableScenario, path: String): Unit = {
    val planInfo = getPlanInfo(scenario)
    writePlanInfo(planInfo, path)
    logger.info(s"Wrote ${planInfo.size} plans to the folder ${path}")

    val personInfo = getPersonInfo(scenario)
    writePersonInfo(personInfo, path)
    logger.info(s"Wrote ${personInfo.size} persons to the folder ${path}")

    writeHouseholds(scenario, path)
    logger.info(s"Wrote ${scenario.getHouseholds.getHouseholds.size()} households to the folder ${path}")
  }

  private def writePlanInfo(plans: Iterable[PlanElement], path: String): Unit = {
    writeCSV(path + "/plans.csv", Seq("personId", "planElement", "activityType", "x", "y", "endTime", "mode")) {
      plans.map { planInfo =>
        Map(
          "personId"     -> planInfo.personId.id,
          "planElement"  -> planInfo.planElement,
          "activityType" -> planInfo.activityType.getOrElse(""),
          "x"            -> planInfo.x.map(_.toString).getOrElse(""),
          "y"            -> planInfo.y.map(_.toString).getOrElse(""),
          "endTime"      -> planInfo.endTime.map(_.toString).getOrElse(""),
          "mode"         -> planInfo.mode.getOrElse(""),
        )
      }
    }
  }
  private def writePersonInfo(persons: Iterable[PersonInfo], path: String): Unit = {
    writeCSV(path + "/persons.csv", Seq("person_id", "household_id", "age")) {
      persons.map { personInfo =>
        Map(
          "person_id"    -> personInfo.personId.id,
          "household_id" -> personInfo.householdId.id,
          "age"          -> personInfo.age.toString
        )
      }
    }
  }

  private def writeHouseholds(scenario: MutableScenario, path: String): Unit = {
    writeCSV(path + "/households.csv", Seq("household_id", "cars", "income", "homecoordx", "homecoordy")) {
      val hhAttrib = scenario.getHouseholds.getHouseholdAttributes
      scenario.getHouseholds.getHouseholds.asScala.map {
        case (id, hh) =>
          val hid = hh.getId.toString
          val income = hh.getIncome.getIncome.toString
          val cars = hh.getVehicleIds.size.toDouble.toString
          // Write coordinates as it is, no conversion to WGS84
          val x = hhAttrib.getAttribute(hid, "homecoordx").toString
          val y = hhAttrib.getAttribute(hid, "homecoordy").toString
          Map("household_id" -> hid, "cars" -> cars, "income" -> income, "homecoordx" -> x, "homecoordy" -> y)
      }
    }
  }

  private def writeCSV(path: String, headers: Seq[String])(rows: Iterable[Map[String, String]]): Unit = {
    FileUtils.using(new CsvMapWriter(new FileWriter(path), CsvPreference.STANDARD_PREFERENCE)) { writer =>
      writer.writeHeader(headers: _*)
      val headersArray = headers.toArray

      rows.foreach { row =>
        writer.write(row.asJava, headersArray: _*)
      }
    }
  }

  private def getPersonIdToHousehold(scenario: MutableScenario): Map[Id[Person], Household] = {
    scenario.getHouseholds.getHouseholds.asScala.toSeq.flatMap {
      case (id, hh) =>
        hh.getMemberIds.asScala.map { person =>
          person -> hh
        }
    }.toMap
  }

  def getPlanInfo(scenario: Scenario): Iterable[PlanElement] = {
    scenario.getPopulation.getPersons.asScala.flatMap {
      case (id, person) =>
        // We get only selected plan!
        Option(person.getSelectedPlan).map { plan =>
          plan.getPlanElements.asScala.zipWithIndex.map {
            case (planElement, index) =>
              toPlanInfo(plan.getPerson.getId.toString, planElement, index)
          }
        }
    }.flatten
  }

  private def toPlanInfo(personId: String, planElement: MatsimPlanElement, index: Int): PlanElement = {
    planElement match {
      case leg: Leg =>
        // Set mode to None, if it's empty string
        val mode = Option(leg.getMode).flatMap { mode =>
          if (mode == "") None
          else Some(mode)
        }

        PlanElement(
          personId = PersonId(personId),
          planElement = "leg",
          planElementIndex = index,
          activityType = None,
          x = None,
          y = None,
          endTime = None,
          mode = mode
        )
      case act: Activity =>
        PlanElement(
          personId = PersonId(personId),
          planElement = "activity",
          planElementIndex = index,
          activityType = Option(act.getType),
          x = Option(act.getCoord.getX),
          y = Option(act.getCoord.getY),
          endTime = Option(act.getEndTime),
          mode = None
        )
    }
  }
  private def getPersonInfo(scenario: MutableScenario): Iterable[PersonInfo] = {
    val personAttrib = scenario.getPopulation.getPersonAttributes
    val personIdToHousehold = getPersonIdToHousehold(scenario)

    scenario.getPopulation.getPersons.asScala.map {
      case (id, person) =>
        val personId = person.getId.toString
        val householdId = personIdToHousehold.get(person.getId).map(_.getId.toString).getOrElse {
          logger.warn(s"Person[$personId] has no household!")
          ""
        }
        val rank = personAttrib.getAttribute(personId, "rank").toString.toInt
        // There is no `age` attribute in matsim scenario, so will set it to 0
        val age = Option(personAttrib.getAttribute(personId, "age")).map(_.toString.toInt).getOrElse(0)
        PersonInfo(personId = PersonId(personId), householdId = HouseholdId(householdId), rank = rank, age = age)
    }
  }
}
