package beam.utils.data.synthpop

import java.util.concurrent.TimeUnit

import beam.utils.Statistics
import beam.utils.data.ctpp.JointDistribution

trait WorkedDurationGenerator {

  /** Gives back the next worked duration
    * @param   rangeWhenLeftHome   The range in seconds, in 24 hours, when a person left a home
    * @return Worked duration in seconds
    */
  def next(rangeWhenLeftHome: Range): Int
}

class WorkedDurationGeneratorImpl(pathToCsv: String, randomSeed: Int) extends WorkedDurationGenerator {
  private val jd = JointDistribution.fromCsvFile(pathToCsv = pathToCsv, seed = randomSeed,
    columnMapping = Map(
      "startTimeIndex" -> JointDistribution.RANGE_COLUMN_TYPE,
      "durationIndex" -> JointDistribution.RANGE_COLUMN_TYPE,
      "probability" -> JointDistribution.DOUBLE_COLUMN_TYPE
    ))

  /** Gives back the next worked duration
   *
   * @param   rangeWhenLeftHome The range in seconds, in 24 hours, when a person left a home
   * @return Worked duration in seconds
   */
  override def next(rangeWhenLeftHome: Range): Int = {
    val startHour = TimeUnit.SECONDS.toHours(rangeWhenLeftHome.start)
    val endHour = TimeUnit.SECONDS.toHours(rangeWhenLeftHome.end)
    val startTimeIndexStr = s"$startHour, $endHour"
    val sample = jd.getSample(true, ("startTimeIndex", Left(startTimeIndexStr)))
    val workDuration = sample("durationIndex").toDouble
    TimeUnit.HOURS.toSeconds(workDuration.toLong).toInt
  }
}

object WorkedDurationGeneratorImpl {
  def main(args: Array[String]): Unit = {
    val path = """D:\Work\beam\Austin\input\work_activities_all_us.csv"""
    val w = new WorkedDurationGeneratorImpl(path, 42)
    val timeWhenLeaveHome = Range(TimeUnit.HOURS.toSeconds(10).toInt, TimeUnit.HOURS.toSeconds(11).toInt)
    val allDurations = (1 to 10000).map { _ =>
      w.next(timeWhenLeaveHome) / 3600.0
    }

    println(s"Duration stats: ${Statistics(allDurations.map(_.toDouble))}")
  }
}
