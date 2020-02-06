package beam.utils.data.ctpp

import beam.utils.csv.GenericCsvReader
import beam.utils.data.ctpp.Models.CTPPEntry

import scala.util.Try

object CTPPParser {

  def readTable(path: String, predicate: CTPPEntry => Boolean = x => true): Seq[CTPPEntry] = {
    val (it, toClose) = GenericCsvReader.readAs[CTPPEntry](path, toCTTPEntry, predicate)
    try {
      it.toVector
    } finally {
      Try(toClose.close())
    }
  }

  def main(args: Array[String]): Unit = {
    val pathToDocFolder = "D:/Work/beam/Austin/2012-2016 CTPP documentation/"
    val pathToStateFolder = "D:/Work/beam/Austin/2012-2016 CTPP documentation/tx/48/"

  }
  private[ctpp] def toCTTPEntry(rec: java.util.Map[String, String]): CTPPEntry = {
    val geoId = GenericCsvReader.getIfNotNull(rec, "GEOID").toString
    val tblId = GenericCsvReader.getIfNotNull(rec, "TBLID").toString
    val lineNo = GenericCsvReader.getIfNotNull(rec, "LINENO").toInt
    val estimate = GenericCsvReader.getIfNotNull(rec, "EST").toString.replaceAll(",", "").toDouble
    val marginOfError = GenericCsvReader.getIfNotNull(rec, "MOE").toString
    CTPPEntry(geoId = geoId, tblId = tblId, lineNumber = lineNo, estimate = estimate, marginOfError = marginOfError)
  }
}
