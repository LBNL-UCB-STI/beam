package beam.utils.transit

import java.nio.file.{Path, Paths}

import beam.utils.transit.GtfsUtils.{TimeFrame, TripAndStopTimes}
import org.onebusaway.gtfs.services.GtfsMutableRelationalDao
import org.onebusaway.gtfs_transformer.services.{GtfsTransformStrategy, TransformContext}

/**
  * Run directly from CLI with, for example:
  * {{{
  *   ./gradlew execute -PmainClass=beam.utils.transit.GtfsFeedAdjuster -PappArgs=\
  *   "[
  *     '--op', 'double|scale',
  *     '--multiplier', '0.5',
  *     '--startTime', '36000',
  *     '--endTime', '57600',
  *     '--in', 'test/input/sf-light/r5/BA.zip',
  *     '--out', 'test/input/sf-light/r5/BA-out.zip'
  *   ]"
  * }}}
  */
object GtfsFeedAdjuster extends App {

  final case class GtfsFeedAdjusterConfig(
    strategy: (Seq[TripAndStopTimes], Double, TimeFrame) => GtfsTransformStrategy = (_, _, _) => NoOpTransformStrategy,
    multiplier: Double = 1.0,
    timeFrame: TimeFrame = TimeFrame.WholeDay,
    in: Path = Paths.get("."),
    out: Path = Paths.get(".")
  )

  final object NoOpTransformStrategy extends GtfsTransformStrategy {
    override def run(context: TransformContext, dao: GtfsMutableRelationalDao): Unit = ()
  }

  def parseArgs(args: Array[String]): GtfsFeedAdjusterConfig = {
    args
      .sliding(2, 2)
      .toList
      .foldLeft(GtfsFeedAdjusterConfig()) {
        case (config, Array("--op", "double")) =>
          config.copy(strategy = (ts, m, tf) => GtfsUtils.doubleTripsStrategy(ts, m.toInt, tf))
        case (config, Array("--op", "scale")) =>
          config.copy(strategy = (ts, m, tf) => GtfsUtils.scaleTripsStrategy(ts, m, tf))
        case (config, Array("--multiplier", value)) => config.copy(multiplier = value.toDouble)
        case (config, Array("--in", path))          => config.copy(in = Paths.get(path))
        case (config, Array("--out", path))         => config.copy(out = Paths.get(path))
        case (config, Array("--startTime", s))      => config.copy(timeFrame = config.timeFrame.copy(startTime = s.toInt))
        case (config, Array("--endTime", s))        => config.copy(timeFrame = config.timeFrame.copy(endTime = s.toInt))
        case (_, arg)                               => throw new IllegalArgumentException(arg.mkString(" "))
      }
  }

  val adjusterConfig = parseArgs(args)

  val trips = GtfsUtils.loadTripsFromGtfs(adjusterConfig.in)
  GtfsUtils.transformGtfs(
    adjusterConfig.in,
    adjusterConfig.out,
    List(
      adjusterConfig.strategy(trips, adjusterConfig.multiplier, adjusterConfig.timeFrame)
    )
  )

}
