package beam.utils.csv.writers

import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.Scenario
import org.matsim.households.{Household, HouseholdUtils}
import org.matsim.utils.objectattributes.ObjectAttributes

import scala.collection.JavaConverters._
import scala.util.Try
import beam.utils.scenario.{HouseholdId, HouseholdInfo}
import ScenarioCsvWriter._
import beam.utils.{OutputDataDescriptor, OutputDataDescriptorObject}

object HouseholdsCsvWriter extends ScenarioCsvWriter with StrictLogging {

  override protected val fields: Seq[String] =
    Seq("householdId", "cars", "incomeValue", "locationX", "locationY")

  override def contentIterator(scenario: Scenario): Iterator[String] = {
    val households = scenario.getHouseholds.getHouseholds.asScala.values
    households.toIterator.map { h: Household =>
      val id = h.getId.toString
      val info = HouseholdInfo(
        householdId = HouseholdId(id),
        income = h.getIncome.getIncome,
        locationX = Try(HouseholdUtils.getHouseholdAttribute(h, "homecoordx").toString.toDouble).getOrElse(0),
        locationY = Try(HouseholdUtils.getHouseholdAttribute(h, "homecoordy").toString.toDouble).getOrElse(0),
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
      info.householdId.id,
      info.cars,
      info.income,
      info.locationX,
      info.locationY
    ).mkString("", FieldSeparator, LineSeparator)
  }

  def outputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("HouseholdsCsvWriter", "households.csv.gz")(
      """
          householdId | Ids of households that are presented in the simulation
          cars | Household cars
          incomeValue | Household income
          locationX | X part of location of the home
          locationY | Y part of location of the home
        """
    )
}
