package beam.calibration.api
import java.nio.file.Paths

import scala.io.Source

import org.matsim.core.utils.io.IOUtils

import beam.analysis.plots.ModeChosenStats
import beam.utils.FileUtils._

trait RunEvaluator[A] {
  def evaluateFromRun(runData: A): Double
}

object EvaluationUtil {

  def evaluate[A](data: A)(implicit ev: RunEvaluator[A]): Double = {
    ev.evaluateFromRun(data)
  }
}

trait ObjectiveFunction {
  def evaluateFromRun(runDataDir:String): Double
}

abstract class FileBasedObjectiveFunction(
  benchmarkFileDataLoc: String,
  outputFileLoc: Option[String] = None
) extends ObjectiveFunction {


  override def evaluateFromRun(runDataDir: String): Double = {
    val benchmarkData: Map[String, Double] = getStatsFromFile(benchmarkFileDataLoc)
    val runData = getStatsFromFile(
      Paths
        .get(runDataDir, ModeChosenStats.MODE_CHOICE_CSV_FILE_NAME)
        .toAbsolutePath
        .toString
    )
    if (outputFileLoc.isDefined) {
      FileBasedObjectiveFunction.writeStatsToFile(runData, outputFileLoc.get)
    }

    compareStats(benchmarkData, runData)
  }

  def compareStats(benchmarkData: Map[String, Double], runData: Map[String, Double]): Double

  def getStatsFromFile(fileLoc: String): Map[String, Double] = {
    using(Source.fromFile(fileLoc)) { source =>
      source.getLines().drop(1).map { _.split(",") }.map(arr => arr(0) -> arr(1).toDouble).toMap
    }
  }
}

object FileBasedObjectiveFunction {

  //TODO: Generalize and move to CSVUtils
  def writeStatsToFile(stats: Map[String, Double], outputFileLoc: String): Unit = {
    using(IOUtils.getBufferedWriter(outputFileLoc))(writer => {
      stats foreach {
        case (k, v) =>
          writer.write(s"$k,$v")
          writer.flush()
      }
    })

  }
}
