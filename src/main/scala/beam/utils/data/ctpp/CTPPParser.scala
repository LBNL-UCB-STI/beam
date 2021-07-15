package beam.utils.data.ctpp

import beam.utils.csv.GenericCsvReader
import beam.utils.data.ctpp.Models.CTPPEntry
import com.typesafe.scalalogging.LazyLogging

import scala.util.Try
import scala.util.control.NonFatal

object CTPPParser extends LazyLogging {

  def readTable(path: String, predicate: CTPPEntry => Boolean = _ => true): Seq[CTPPEntry] = {
    def predicateOpt: Option[CTPPEntry] => Boolean = x => x.exists(predicate)

    val (it, toClose) = GenericCsvReader.readAs[Option[CTPPEntry]](path, toCTTPEntry, predicateOpt)
    try {
      it.flatten.toVector
    } finally {
      Try(toClose.close())
    }
  }

  def main(args: Array[String]): Unit = {}

  private[ctpp] def toCTTPEntry(rec: java.util.Map[String, String]): Option[CTPPEntry] = {
    val geoId = GenericCsvReader.getIfNotNull(rec, "GEOID")
    val tblId = GenericCsvReader.getIfNotNull(rec, "TBLID")
    val lineNo = GenericCsvReader.getIfNotNull(rec, "LINENO").toInt
    val estimateStr = GenericCsvReader.getIfNotNull(rec, "EST").replaceAll(",", "")
    val maybeEstimate =
      try {
        Some(estimateStr.toDouble)
      } catch {
        case NonFatal(_) =>
          // TODO Better error propagation to the caller.
          // logger.info(s"Can't convert estimate '$estimateStr' to double", ex)
          None
      }
    val marginOfError = GenericCsvReader.getIfNotNull(rec, "MOE")
    maybeEstimate.map(estimate =>
      CTPPEntry(geoId = geoId, tblId = tblId, lineNumber = lineNo, estimate = estimate, marginOfError = marginOfError)
    )
  }
}
