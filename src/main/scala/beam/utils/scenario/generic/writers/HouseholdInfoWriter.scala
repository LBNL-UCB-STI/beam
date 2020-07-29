package beam.utils.scenario.generic.writers

import beam.utils.csv.CsvWriter
import beam.utils.scenario.{HouseholdInfo, PersonInfo}

import scala.util.Try

trait HouseholdInfoWriter {
  def write(path: String, xs: Iterable[HouseholdInfo]): Unit
}

class CsvHouseholdInfoWriter(val path: String) extends AutoCloseable {
  import CsvHouseholdInfoWriter._

  private val csvWriter = new CsvWriter(path, headers)

  def write(xs: Iterable[HouseholdInfo]): Unit = {
    writeTo(xs, csvWriter)
  }

  override def close(): Unit = {
    Try(csvWriter.close())
  }
}

object CsvHouseholdInfoWriter extends HouseholdInfoWriter {
  private val headers: Array[String] = Array("householdId", "cars", "incomeValue", "locationX", "locationY")

  override def write(path: String, xs: Iterable[HouseholdInfo]): Unit = {
    val csvWriter = new CsvWriter(path, headers)
    try {
      writeTo(xs, csvWriter)
    } finally {
      Try(csvWriter.close())
    }
  }

  private def writeTo(xs: Iterable[HouseholdInfo], csvWriter: CsvWriter): Unit = {
    xs.foreach { household =>
      csvWriter.write(
        household.householdId.id,
        household.cars,
        household.income,
        household.locationX,
        household.locationY
      )
    }
  }
}
