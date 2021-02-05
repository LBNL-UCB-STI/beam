package beam.calibration.impl.example

import java.io.File
import java.net.URI

import scala.util.Try

import beam.calibration.impl.example.ModeChoiceObjectiveFunction.ModeChoiceStats
import beam.utils.FileUtils
import io.circe._
import io.circe.generic.semiauto._
import io.circe.parser._
import org.apache.http.client.fluent.{Content, Request}

class ModeChoiceObjectiveFunction(benchmarkDataFileLoc: String) {

  implicit val modeChoiceDataDecoder: Decoder[ModeChoiceStats] = deriveDecoder[ModeChoiceStats]

  def evaluateFromRun(runDataFileLoc: String, comparisonType: ErrorComparisonType.Value): Double = {

    val benchmarkData = if (benchmarkDataFileLoc.contains("http://")) {
      getStatsFromMTC(new URI(runDataFileLoc))
    } else {
      getStatsFromFile(benchmarkDataFileLoc)
    }

    if (comparisonType == ErrorComparisonType.AbsoluteError) {
      compareStatsAbsolutError(benchmarkData, getStatsFromFile(runDataFileLoc))
    } else if (comparisonType == ErrorComparisonType.AbsoluteErrorWithPreferenceForModeDiversity) {
      compareStatsAbsolutError(benchmarkData, getStatsFromFile(runDataFileLoc)) + getStatsFromFile(runDataFileLoc).size * 0.1
    } else if (comparisonType == ErrorComparisonType.AbsoluteErrorWithMinLevelRepresentationOfMode) {
      val runModeStats = getStatsFromFile(runDataFileLoc)
      var objective = compareStatsAbsolutError(benchmarkData, runModeStats) + getStatsFromFile(runDataFileLoc).size * 0.1

      if (minLevelRepresentationOfMode(runModeStats, benchmarkData, 0.8, "car")) {
        objective = objective + 0.1
      }

      if (minLevelRepresentationOfMode(runModeStats, benchmarkData, 0.3, "drive_transit")) {
        objective = objective + 0.1
      }

      if (minLevelRepresentationOfMode(runModeStats, benchmarkData, 0.3, "ride_hail")) {
        objective = objective + 0.1
      }

      if (minLevelRepresentationOfMode(runModeStats, benchmarkData, 0.3, "ride_hail_pooled")) {
        objective = objective + 0.1
      }

      if (minLevelRepresentationOfMode(runModeStats, benchmarkData, 0.3, "ride_hail_transit")) {
        objective = objective + 0.1
      }

      if (minLevelRepresentationOfMode(runModeStats, benchmarkData, 0.3, "walk")) {
        objective = objective + 0.1
      }

      if (minLevelRepresentationOfMode(runModeStats, benchmarkData, 0.3, "walk_transit")) {
        objective = objective + 0.1
      }

      objective
    } else {
      compareStatsRMSPE(benchmarkData, getStatsFromFile(runDataFileLoc))
    }

  }

  def minLevelRepresentationOfMode(
    runModeStats: Map[String, Double],
    benchmarkData: Map[String, Double],
    minLevelRepresentationOfMode: Double,
    mode: String
  ): Boolean = {
    runModeStats.contains(mode) && (runModeStats(mode)
    - benchmarkData(mode) * minLevelRepresentationOfMode) > 0
  }

  def compareStatsAbsolutError(
    benchmarkData: Map[String, Double],
    runData: Map[String, Double]
  ): Double = {
    val res =
      runData
        .flatMap({
          case (k, y_hat) =>
            benchmarkData
              .get(k)
              .map { y =>
                Math.abs(y - y_hat)
              }
        })
        .sum
    -res
  }

  /**
    * Computes MPE between run data and benchmark data on a mode-to-mode basis.
    *
    * @param benchmarkData target values of mode shares
    * @param runData       output values of mode shares given current suggestion.
    * @return the '''negative''' RMSPE value (since we '''maximize''' the objective).
    */
  def compareStatsRMSPE(
    benchmarkData: Map[String, Double],
    runData: Map[String, Double]
  ): Double = {
    val res = -Math.sqrt(
      runData.flatMap {
        case (k, y_hat) =>
          benchmarkData
            .get(k)
            .map { y =>
              Math.pow((y - y_hat) / y, 2)
            }
      }.sum / runData.size
    )
    res
  }

  def getStatsFromFile(fileLoc: String): Map[String, Double] = {
    val lines = FileUtils.readAllLines(fileLoc).toArray
    val header = lines.head.split(",").tail
    val lastIter = lines.reverse.head.split(",").tail.map(_.toDouble)
    val total = lastIter.sum
    val pcts = lastIter.map(x => x / total)
    header.zip(pcts).toMap
  }

  def getStatsFromMTC(mtcBenchmarkEndPoint: URI): Map[String, Double] = {
    (for {
      mtcContent         <- getMTCContent(mtcBenchmarkEndPoint)
      parsedData         <- parseMTCRequest(mtcContent)
      modeChoiceStatList <- jsonToModechoiceStats(parsedData)
    } yield {
      modeChoiceStatList.map { stat =>
        stat.mode -> stat.share
      }.toMap
    }).getOrElse(Map.empty[String, Double])
  }

  def getMTCContent(benchmarkDataFileLoc: URI): Try[String] = {
    Try {
      val content: Content = Request.Get(benchmarkDataFileLoc).execute.returnContent
      content.asString()
    }
  }

  def parseMTCRequest(rawResponseData: String): Try[Json] = {
    parse(rawResponseData).toTry
  }

  def jsonToModechoiceStats(modechoiceJson: Json): Try[List[ModeChoiceStats]] = {
    Try {
      modechoiceJson.as[List[ModeChoiceStats]].right.getOrElse(List.empty[ModeChoiceStats])
    }
  }

}

object ModeChoiceObjectiveFunction {

  case class ModeChoiceStats(
    year: String,
    source: String,
    region: String,
    share: Double,
    mode: String,
    data_type: String
  )

}

object ErrorComparisonType extends Enumeration {

  val RMSPE, AbsoluteError, AbsoluteErrorWithPreferenceForModeDiversity, AbsoluteErrorWithMinLevelRepresentationOfMode =
    Value
}
