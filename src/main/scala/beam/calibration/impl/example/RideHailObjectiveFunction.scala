package beam.calibration.impl.example

import scala.io.Source

import beam.calibration.api.ObjectiveFunction
import beam.utils.FileUtils.using

object RideHailObjectiveFunction {

  def evaluateFromRun(runDataPath: String): Double = {
    val rideHailStats = getStatsFromFile(runDataPath)
    val reservationCount = rideHailStats("reservationCount")
    reservationCount
  }

  def getStatsFromFile(fileLoc: String): Map[String, Double] = {
    val lines = Source.fromFile(fileLoc).getLines().toArray
    val header = lines.head.split(",").tail
    val lastIter = lines.reverse.head.split(",").tail.map(_.toDouble)
    val total = lastIter.sum
    val pcts = lastIter.map(x => x / total)
    header.zip(pcts).toMap
  }
}
