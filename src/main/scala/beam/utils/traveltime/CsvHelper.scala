package beam.utils.traveltime

import java.io.Writer

import beam.utils.traveltime.CsvHelper.Delimiter

trait CsvHelper {
  implicit val delimiter: Delimiter = Delimiter(",")

  def writeColumnValue(value: String)(implicit wrt: Writer, delimiter: Delimiter): Unit = {
    wrt.append(value)
    wrt.append(delimiter.delimiter)
  }
}

object CsvHelper {
  case class Delimiter(delimiter: String)
}
