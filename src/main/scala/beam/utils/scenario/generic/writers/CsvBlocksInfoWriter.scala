package beam.utils.scenario.generic.writers

import beam.utils.csv.CsvWriter
import beam.utils.scenario.{BlockInfo, HouseholdInfo}

import scala.util.Try

trait CsvBlocksInfoWriter {
  def write(path: String, xs: Iterable[BlockInfo]): Unit
}

object CsvBlocksInfoWriter extends CsvBlocksInfoWriter {
  private val headers: Array[String] = Array("blockId", "x", "y")

  override def write(path: String, xs: Iterable[BlockInfo]): Unit = {
    val csvWriter = new CsvWriter(path, headers)
    try {
      xs.foreach { household =>
        csvWriter.write(
          household.blockId.id,
          household.x,
          household.y
        )
      }
    } finally {
      Try(csvWriter.close())
    }
  }
}
