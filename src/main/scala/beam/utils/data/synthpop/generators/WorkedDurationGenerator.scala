package beam.utils.data.synthpop.generators

import java.util.concurrent.TimeUnit

import beam.utils.{MathUtils, ProfilingUtils, Statistics}
import beam.utils.data.ctpp.JointDistribution
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.math3.random.{MersenneTwister, RandomGenerator}

import scala.util.control.NonFatal

trait WorkedDurationGenerator {

  /** Gives back the next worked duration
    * @param   rangeWhenLeftHome   The range in seconds, in 24 hours, when a person left a home
    * @return Worked duration in seconds
    */
  def next(rangeWhenLeftHome: Range): Int
}

class WorkedDurationGeneratorImpl(val pathToCsv: String, val rndGen: RandomGenerator)
    extends WorkedDurationGenerator
    with LazyLogging {
  private val jd = JointDistribution.fromCsvFile(
    pathToCsv = pathToCsv,
    rndGen = rndGen,
    columnMapping = Map(
      "startTimeIndex" -> JointDistribution.RANGE_COLUMN_TYPE,
      "durationIndex"  -> JointDistribution.RANGE_COLUMN_TYPE,
      "probability"    -> JointDistribution.DOUBLE_COLUMN_TYPE
    )
  )

  /** Gives back the next worked duration
    *
    * @param   rangeWhenLeftHome The range in seconds, in 24 hours, when a person left a home
    * @return Worked duration in seconds
    */
  override def next(rangeWhenLeftHome: Range): Int = {
    val startHour = MathUtils.roundToFraction(rangeWhenLeftHome.start / 3600.0, 2)
    val endHour = MathUtils.roundToFraction(rangeWhenLeftHome.end / 3600.0, 2)
    val startTimeIndexStr = s"$startHour, $endHour"
    try {
      val sample = jd.getSample(true, ("startTimeIndex", Left(startTimeIndexStr)))
      val workDurationInHours = sample("durationIndex").toDouble
      val workDurationInSeconds = (workDurationInHours * 3600).toInt
      workDurationInSeconds
    } catch {
      case NonFatal(ex) =>
        logger.warn(
          s"Can't compute worked duration. startTimeIndexStr: '$startTimeIndexStr',  rangeWhenLeftHome: $rangeWhenLeftHome. Error: ${ex.getMessage}",
          ex
        )
        TimeUnit.HOURS.toSeconds(7).toInt
    }
  }

}

object WorkedDurationGeneratorImpl {

  def main(args: Array[String]): Unit = {
    val path = "D:/Work/beam/NewYork/work_activities_35620.csv"
    val w = new WorkedDurationGeneratorImpl(path, new MersenneTwister(42))
    // val timeWhenLeaveHome = Range(TimeUnit.HOURS.toSeconds(10).toInt, TimeUnit.HOURS.toSeconds(11).toInt)
    val timeWhenLeaveHome = Range(14400, 15300)

    val n: Int = 10000
    val allDurations = ProfilingUtils.timed(s"Generated $n work durations", x => println(x)) {
      (1 to n).map { _ =>
        w.next(timeWhenLeaveHome) / 3600.0
      }
    }

    println(s"Duration stats: ${Statistics(allDurations.map(_.toDouble))}")
  }
}
