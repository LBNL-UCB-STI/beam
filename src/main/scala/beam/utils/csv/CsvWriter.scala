package beam.utils.csv

import java.io.Writer
import org.matsim.core.utils.io.IOUtils
import scala.util.Try

case class Delimiter(value: String = ",")
case class LineSeparator(value: String = System.lineSeparator())

class CsvWriter(
  path: String,
  headers: IndexedSeq[String],
  implicit val delimiter: Delimiter = Delimiter(),
  implicit val lineSeparator: LineSeparator = LineSeparator()
) extends AutoCloseable {
  implicit val writer: Writer = IOUtils.getBufferedWriter(path)
  CsvWriter.writeHeader(headers)

  def writeColumn(value: Any, shouldAddDelimiter: Boolean = true): Unit = {
    CsvWriter.writeColumnValue(value, shouldAddDelimiter)
  }

  def write(xs: Any*): Unit = {
    writeRow(xs.toVector)
  }

  def writeRow(values: IndexedSeq[Any]): Unit = {
    values.zipWithIndex.foreach {
      case (value, idx) =>
        val shouldAddDelimiter = idx != values.length - 1
        CsvWriter.writeColumnValue(value, shouldAddDelimiter)
    }
    CsvWriter.writeLineSeparator
  }

  def flush(): Unit = {
    writer.flush()
  }

  def writeNewLine(): Unit = {
    CsvWriter.writeLineSeparator
  }

  override def close(): Unit = {
    Try(writer.close())
  }
}

object CsvWriter {

  def writeColumnValue(
    value: Any,
    shouldAddDelimiter: Boolean = true
  )(implicit wrt: Writer, delimiter: Delimiter): Unit = {
    // Make sure Option is handled properly (None -> "", Some(x) -> x)
    val toWrite = value match {
      case None    => ""
      case Some(x) => x
      case x       => x
    }
    wrt.append(toWrite.toString)
    if (shouldAddDelimiter)
      wrt.append(delimiter.value)
  }

  def writeHeader(
    headers: IndexedSeq[String]
  )(implicit wrt: Writer, delimiter: Delimiter, lineSeparator: LineSeparator): Unit = {
    headers.zipWithIndex.foreach {
      case (header, idx) =>
        val shouldAddDelimiter = idx != headers.size - 1
        writeColumnValue(header, shouldAddDelimiter)
    }
    wrt.append(lineSeparator.value)
    wrt.flush()
  }

  def writeLineSeparator(implicit wrt: Writer, lineSeparator: LineSeparator): Unit = {
    wrt.write(lineSeparator.value)
  }
}
