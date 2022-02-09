package beam.utils.scenario.generic.writers

import beam.utils.csv.CsvWriter
import beam.utils.data.synthpop.SimpleScenarioGenerator.logger
import beam.utils.scenario.HouseholdInfo
import com.typesafe.scalalogging.LazyLogging

import scala.util.Try

trait HouseholdInfoWriter {
  def write(path: String, xs: Iterable[HouseholdInfo]): Unit
}

object CsvHouseholdInfoWriter extends HouseholdInfoWriter with LazyLogging {
  private val headers: Array[String] = Array("householdId", "cars", "incomeValue", "locationX", "locationY")

  override def write(path: String, xs: Iterable[HouseholdInfo]): Unit = {
    val csvWriter = new CsvWriter(path, headers)
    try {
      xs.foreach { household =>
        csvWriter.write(
          household.householdId.id,
          household.cars,
          household.income,
          household.locationX,
          household.locationY
        )
      }
      logger.info(s"Wrote households information to $path")
    } finally {
      csvWriter.close()
    }
  }
}
