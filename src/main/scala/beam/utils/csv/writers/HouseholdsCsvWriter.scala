package beam.utils.csv.writers

import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.Scenario
import org.matsim.households.Household
import org.matsim.utils.objectattributes.ObjectAttributes
import scala.collection.JavaConverters._
import scala.util.Try

import beam.utils.scenario.{HouseholdId, HouseholdInfo}

object HouseholdsCsvWriter extends ScenarioCsvWriter with StrictLogging {

  override protected val fields: Seq[String] =
    Seq("householdId", "incomeValue", "locationX", "locationY")

  override def contentIterator(scenario: Scenario): Iterator[String] = {
    val attributes: ObjectAttributes = scenario.getHouseholds.getHouseholdAttributes
    val households = scenario.getHouseholds.getHouseholds.asScala.values
    households.toIterator.map { h: Household =>
      val id = h.getId.toString
      val info = HouseholdInfo(
        householdId = HouseholdId(id),
        income = h.getIncome.getIncome,
        locationX = Try(attributes.getAttribute(id, "homecoordx").toString.toDouble).getOrElse(0),
        locationY = Try(attributes.getAttribute(id, "homecoordy").toString.toDouble).getOrElse(0),
        cars = h.getVehicleIds.size()
      )
      toLine(info)
    }
  }

  override def contentIterator[A](elements: Iterator[A]): Iterator[String] = {
    elements.flatMap {
      case info: HouseholdInfo => Some(toLine(info))
      case _                   => None
    }
  }

  private def toLine(info: HouseholdInfo): String = {
    Seq(
      info.householdId,
      info.income,
      info.locationX,
      info.locationY
    ).mkString("", FieldSeparator, LineSeparator)
  }

}
