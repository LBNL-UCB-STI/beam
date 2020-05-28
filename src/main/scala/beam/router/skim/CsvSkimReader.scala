package beam.router.skim

import java.io.File
import java.util

import com.typesafe.scalalogging.Logger
import com.univocity.parsers.common.record.Record
import com.univocity.parsers.csv.{CsvParser, CsvParserSettings}
import org.matsim.core.utils.io.IOUtils

import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.util.Try
import scala.util.control.NonFatal

/**
  * Skims csv reader.
  * @param aggregatedSkimsFilePath path to skims file
  * @param fromCsv column mapping function (depends on skimmer type)
  * @param logger passing logger from skimmer as this is invoked by various skimmers. This helps distinguishing in the
  *               log which skimmer has an issue reading skims.
  */
class CsvSkimReader(
  val aggregatedSkimsFilePath: String,
  fromCsv: scala.collection.Map[String, String] => (AbstractSkimmerKey, AbstractSkimmerInternal),
  logger: Logger
) {

  val header: Array[String] = initHeader()

  def readAggregatedSkims: immutable.Map[AbstractSkimmerKey, AbstractSkimmerInternal] = {
    var res = Map.empty[AbstractSkimmerKey, AbstractSkimmerInternal]
    val csvParser: CsvParser = getCsvParser
    try {
      if (new File(aggregatedSkimsFilePath).isFile) {
        val mapReader = csvParser.iterateRecords(IOUtils.getBufferedReader(aggregatedSkimsFilePath)).asScala
        res = mapReader
          .map(rec => {
            val a = convertRecordToMap(rec, header)
            val newPair = fromCsv(a)
            newPair
          })
          .toMap
        logger.info(s"warmStart skim successfully loaded from path '${aggregatedSkimsFilePath}'")
      } else {
        logger.info(s"warmStart skim NO PATH FOUND '${aggregatedSkimsFilePath}'")
      }
    } catch {
      case NonFatal(ex) =>
        logger.info(s"Could not load warmStart skim from '${aggregatedSkimsFilePath}': ${ex.getMessage}")
    } finally {
      // In any case stop parser
      Try(csvParser.stopParsing())
    }
    res
  }

  private def initHeader(): Array[String] = {
    val csvParser = getCsvParser
    try {
      val rdr = IOUtils.getBufferedReader(aggregatedSkimsFilePath)
      try {
        csvParser.beginParsing(rdr)
        csvParser.getRecordMetadata.headers()
      } finally {
        Try(rdr.close())
      }
    } catch {
      case NonFatal(ex) =>
        logger.info(s"Could not read headers '${aggregatedSkimsFilePath}': ${ex.getMessage}", ex)
        Array.empty
    } finally {
      Try(csvParser.stopParsing())
    }
  }

  private def convertRecordToMap(rec: Record, header: Array[String]): scala.collection.Map[String, String] = {
    val res = new util.HashMap[String, String]()
    rec.fillFieldMap(res, header: _*)
    res.asScala
  }

  private def getCsvParser: CsvParser = {
    val settings = new CsvParserSettings()
    settings.setHeaderExtractionEnabled(true)
    settings.detectFormatAutomatically()
    val csvParser = new CsvParser(settings)
    csvParser
  }

}
