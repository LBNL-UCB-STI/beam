package beam.calibration.impl.example
import scala.io.Source

import beam.calibration.api.ObjectiveFunction
import beam.utils.FileUtils.using

object CountsObjectiveFunction extends ObjectiveFunction {

  override def evaluateFromRun(runDataPath: String): Double = {
    val counts = getStatsFromFile(runDataPath)
    -(counts.map { case (_, sCd) => sCd.map { _.normalizedError }.sum / sCd.size }.sum / counts.size)
  }

  def getStatsFromFile(fileLoc: String): Map[String, Seq[CountData]] = {
    using(Source.fromFile(fileLoc)) { source =>
      val records = source.getLines().drop(1).map { _.split("\t") }.toSeq
      records
        .map(record => CountData(record(1), record(3).toDouble))
        .groupBy(count => count.stationId)
    }
  }
}

case class CountData(stationId: String, normalizedError: Double)
