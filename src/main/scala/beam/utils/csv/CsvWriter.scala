package beam.utils.csv

import com.typesafe.scalalogging.LazyLogging

import java.io.Writer
import org.matsim.core.utils.io.IOUtils

import scala.util.Try
import scala.util.control.NonFatal

case class Delimiter(value: String = ",")
case class LineSeparator(value: String = System.lineSeparator())

class CsvWriter(
  path: String,
  headers: Seq[String],
  implicit val delimiter: Delimiter = Delimiter(),
  implicit val lineSeparator: LineSeparator = LineSeparator()
) extends AutoCloseable
    with LazyLogging {
  def this(path: String, headers: String*) = this(path, headers)

  implicit val writer: Writer = IOUtils.getBufferedWriter(path)
  CsvWriter.writeHeader(headers)

  def writeColumn(value: Any, shouldAddDelimiter: Boolean = true): Unit = {
    CsvWriter.writeColumnValue(value, shouldAddDelimiter)
  }

  def write(xs: Any*): Unit = {
    writeRow(xs.toVector)
  }

  def writeRow(values: Seq[Any]): Unit = {
    values.zipWithIndex.foreach { case (value, idx) =>
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
    try writer.close()
    catch {
      case NonFatal(th) =>
        logger.error(s"Error while closing csv writer", th)
    }
  }

  def writeAllAndClose(rows: Iterable[Seq[Any]]): Try[Unit] = {
    val result = Try(rows.foreach(writeRow))
    close()
    result
  }
}

object CsvWriter {
  def apply(path: String, headers: String*) = new CsvWriter(path, headers)

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
    val strValue = toWrite.toString
    val strValueToAppend =
      if (!strValue.startsWith("\"") && strValue.contains(',')) "\"" + strValue + "\"" else strValue
    wrt.append(strValueToAppend)
    if (shouldAddDelimiter)
      wrt.append(delimiter.value)
  }

  def writeHeader(
    headers: Seq[String]
  )(implicit wrt: Writer, delimiter: Delimiter, lineSeparator: LineSeparator): Unit = {
    headers.zipWithIndex.foreach { case (header, idx) =>
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
