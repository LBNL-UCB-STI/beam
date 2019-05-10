package beam.utils.scripts

import beam.utils.FileUtils
import beam.utils.scripts.HouseholdsWriterCSV._
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.Scenario
import org.matsim.core.api.internal.MatsimWriter
import org.matsim.core.utils.io.AbstractMatsimWriter
import org.matsim.utils.objectattributes.ObjectAttributes

import scala.collection.JavaConverters._

class HouseholdsWriterCSV(
                         scenario: Scenario
                       ) extends AbstractMatsimWriter with StrictLogging
  with MatsimWriter {

  private case class HouseholdEntry(householdId: Int, incomeValue: Double, incomeCurrency: String, locationX: String, locationY:String) {
    override def toString: String = {
      Seq(householdId, incomeValue, incomeCurrency, locationX, locationY)
        .mkString("", ColumnSeparator, LineTerminator)
    }
  }

  override def write(filename: String): Unit = {
    val attributes: ObjectAttributes = scenario.getHouseholds.getHouseholdAttributes

    val households = scenario.getHouseholds.getHouseholds.asScala
    val allEntries: Iterable[HouseholdEntry] = households.values.map{ h=>
      val id = h.getId.toString
      HouseholdEntry(
        householdId = id.toInt,
        incomeValue = h.getIncome.getIncome,
        incomeCurrency = h.getIncome.getCurrency,
        locationX = attributes.getAttribute(id, "homecoordx").toString,
        locationY = attributes.getAttribute(id, "homecoordy").toString
      )
    }

    val header = Iterator(Columns.mkString("", ColumnSeparator, LineTerminator))
    val contentIterator = allEntries.toIterator.map(_.toString)

    FileUtils.writeToFile(filename, header ++ contentIterator)
  }

}


object HouseholdsWriterCSV {

  private val Columns = Seq("householdId","incomeValue","incomeCurrency","locationX","locationY")
  private val ColumnSeparator = ","
  private val LineTerminator = "\n"

  def apply(scenario: Scenario): HouseholdsWriterCSV = {
    new HouseholdsWriterCSV(scenario)
  }

}
