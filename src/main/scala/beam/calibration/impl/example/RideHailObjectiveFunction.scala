package beam.calibration.impl.example

import beam.utils.FileUtils

object RideHailObjectiveFunction {

  def evaluateFromRun(runDataPath: String): Double = {
    val rideHailStats = getStatsFromFile(runDataPath)
    val reservationCount = rideHailStats("reservationCount")
    reservationCount
  }

  def getStatsFromFile(fileLoc: String): Map[String, Double] = {
    val lines = FileUtils.readAllLines(fileLoc).toArray
    val header = lines.head.split(",").tail
    val lastIter = lines.reverse.head.split(",").tail.map(_.toDouble)
    val total = lastIter.sum
    val pcts = lastIter.map(x => x / total)
    header.zip(pcts).toMap
  }
}
