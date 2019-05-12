package beam.utils.csv.writers

import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.Scenario
import org.matsim.households.Household
import org.matsim.utils.objectattributes.ObjectAttributes

import scala.collection.JavaConverters._

object HouseholdsCsvWriter extends ScenarioCsvWriter with StrictLogging {

  override protected val fields: Seq[String] =
    Seq("householdId", "incomeValue", "incomeCurrency", "locationX", "locationY")

  private case class HouseholdEntry(
    householdId: Int,
    incomeValue: Double,
    incomeCurrency: String,
    locationX: String,
    locationY: String
  ) {
    override def toString: String = {
      Seq(householdId, incomeValue, incomeCurrency, locationX, locationY)
        .mkString("", FieldSeparator, LineSeparator)
    }
  }

  override def contentIterator(scenario: Scenario): Iterator[String] = {
    val attributes: ObjectAttributes = scenario.getHouseholds.getHouseholdAttributes

    val households = scenario.getHouseholds.getHouseholds.asScala.values
    households.toIterator.map { h: Household =>
      val id = h.getId.toString
      HouseholdEntry(
        householdId = id.toInt,
        incomeValue = h.getIncome.getIncome,
        incomeCurrency = h.getIncome.getCurrency,
        locationX = attributes.getAttribute(id, "homecoordx").toString,
        locationY = attributes.getAttribute(id, "homecoordy").toString
      ).toString
    }
  }

}
