package beam.utils.scenario.generic.writers

import beam.utils.csv.CsvWriter
import beam.utils.scenario.BlockInfo
import com.typesafe.scalalogging.LazyLogging
import scala.util.Try

trait BlocksInfoWriter {
  def write(path: String, xs: Iterator[BlockInfo]): Unit
}

class CsvBlocksInfoWriter(val path: String) extends AutoCloseable {
  import CsvBlocksInfoWriter._
  private val csvWriter = new CsvWriter(path, headers)

  def write(xs: Iterator[BlockInfo]): Unit = {
    writeTo(xs, csvWriter)
  }

  override def close(): Unit = {
    Try(csvWriter.close())
  }
}

object CsvBlocksInfoWriter extends BlocksInfoWriter with LazyLogging {
  private val headers: Array[String] = Array("blockId", "x", "y")

  override def write(path: String, xs: Iterator[BlockInfo]): Unit = {
    val csvWriter: CsvWriter = new CsvWriter(path, headers)
    try {
      writeTo(xs, csvWriter)
      logger.info(s"Wrote blocks information to $path")
    } finally {
      csvWriter.close()
    }
  }

  private def writeTo(xs: Iterator[BlockInfo], csvWriter: CsvWriter): Unit = {
    xs.foreach { household =>
      csvWriter.write(
        household.blockId.id,
        household.x,
        household.y
      )
    }
  }
}
