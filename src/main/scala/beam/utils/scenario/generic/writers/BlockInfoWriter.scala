package beam.utils.scenario.generic.writers

import beam.utils.csv.CsvWriter
import beam.utils.scenario.BlockInfo

import scala.util.Try

trait BlockInfoWriter {
  def write(path: String, xs: Iterator[BlockInfo]): Unit
}

class CsvBlockInfoWriter(val path: String) extends AutoCloseable {
  import beam.utils.scenario.generic.writers.CsvBlockInfoWriter._

  private val csvWriter = new CsvWriter(path, headers)

  def write(xs: Iterator[BlockInfo]): Unit = {
    writeTo(xs, csvWriter)
  }

  override def close(): Unit = {
    Try(csvWriter.close())
  }
}

object CsvBlockInfoWriter extends BlockInfoWriter {

  private val headers: Array[String] = Array("blockId", "x", "y")

  override def write(path: String, xs: Iterator[BlockInfo]): Unit = {
    val csvWriter = new CsvWriter(path, headers)
    try {
      writeTo(xs, csvWriter)
    } finally {
      Try(csvWriter.close())
    }
  }

  private def writeTo(xs: Iterator[BlockInfo], csvWriter: CsvWriter): Unit = {
    xs.foreach { block =>
      csvWriter.write(
        block.blockId.id,
        block.x,
        block.y
      )
    }
  }
}
