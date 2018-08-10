package beam.calibration.impl.example

import java.net.URI
import java.nio.file.Paths

import beam.analysis.plots.ModeChosenStats
import beam.calibration.api.FileBasedObjectiveFunction
import beam.calibration.impl.example.ModeChoiceObjectiveFunction.ModeChoiceStats
import beam.utils.FileUtils
import io.circe._
import io.circe.generic.semiauto._
import io.circe.parser._
import org.apache.http.client.fluent.{Content, Request}

import scala.io.Source
import scala.util.Try


class ModeChoiceObjectiveFunction(benchmarkDataFileLoc: String) extends FileBasedObjectiveFunction(benchmarkDataFileLoc) {

  implicit val modeChoiceDataDecoder: Decoder[ModeChoiceStats] = deriveDecoder[ModeChoiceStats]

  override def evaluateFromRun(runDataFileDir: String): Double = {
    val benchmarkData = if (benchmarkDataFileLoc.contains("http://")) {
      getStatsFromMTC(new URI(runDataFileDir))
    } else {
      getStatsFromFile(benchmarkDataFileLoc)
    }
    val runData = getStatsFromFile(Paths.get(runDataFileDir, ModeChosenStats.MODE_CHOICE_CSV_FILE_NAME).toAbsolutePath.toString)
    compareStats(benchmarkData, runData)
  }

  /**
    * Computes RMSPE between run data and benchmark data on a mode-to-mode basis.
    *
    * @param benchmarkData target values of mode shares
    * @param runData       output values of mode shares given current suggestion.
    * @return the '''negative''' RMSPE value (since we '''maximize''' the objective).
    */
  def compareStats(benchmarkData: Map[String, Double], runData: Map[String, Double]): Double = {
    val res = -Math.sqrt(runData.map({ case (k, y_hat) =>
      val y = benchmarkData(k)
      Math.pow((y - y_hat) / y, 2)
    }).sum / runData.size)
    res
  }

  def getStatsFromFile(fileLoc: String): Map[String, Double] = {
    FileUtils.using( Source.fromFile(fileLoc)) { source =>
      source.getLines().drop(1).map { _.split(",") }.map(arr =>
        arr(0) -> arr(1).toDouble).toMap
    }
  }

  def getStatsFromMTC(mtcBenchmarkEndPoint: URI): Map[String, Double] = {
    (for {mtcContent <- getMTCContent(mtcBenchmarkEndPoint)
          parsedData <- parseMTCRequest(mtcContent)
          modeChoiceStatList <- jsonToModechoiceStats(parsedData)
    } yield {
      modeChoiceStatList.map { stat => stat.mode -> stat.share }.toMap
    }).get
  }

  def getMTCContent(benchmarkDataFileLoc: URI): Try[String] = {
    Try {
      val content: Content = Request.Get(benchmarkDataFileLoc).execute
        .returnContent
      content.asString()
    }
  }

  def parseMTCRequest(rawResponseData: String): Try[Json] = {
    parse(rawResponseData).toTry
  }

  def jsonToModechoiceStats(modechoiceJson: Json): Try[List[ModeChoiceStats]] = {
    Try {
      modechoiceJson.as[List[ModeChoiceStats]].right.get
    }
  }

}

object ModeChoiceObjectiveFunction {


  case class ModeChoiceStats(year: String, source: String, region: String, share: Double, mode: String, data_type: String)


}


