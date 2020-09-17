package beam.utils.transit

import java.nio.file.{Files, Path, Paths}
import java.util.stream.Collectors

import beam.utils.transit.GtfsUtils.{TimeFrame, TripAndStopTimes}
import com.typesafe.scalalogging.StrictLogging
import org.apache.commons.io.FilenameUtils
import org.onebusaway.gtfs.services.GtfsMutableRelationalDao
import org.onebusaway.gtfs_transformer.services.{GtfsTransformStrategy, TransformContext}

import scala.collection.JavaConverters._

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
object GtfsFeedAdjuster extends App with StrictLogging {

  final case class GtfsFeedAdjusterConfig(
    strategy: String = "multiplication",
    factor: Double = 1.0,
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
        case (config, Array("--op", "multiplication")) =>
          config.copy(strategy = "multiplication")
        case (config, Array("--op", "scale")) =>
          config.copy(strategy = "scale")
        case (config, Array("--factor", value)) => config.copy(factor = value.toDouble)
        case (config, Array("--in", path))      => config.copy(in = Paths.get(path))
        case (config, Array("--out", path))     => config.copy(out = Paths.get(path))
        case (config, Array("--startTime", s))  => config.copy(timeFrame = config.timeFrame.copy(startTime = s.toInt))
        case (config, Array("--endTime", s))    => config.copy(timeFrame = config.timeFrame.copy(endTime = s.toInt))
        case (_, arg)                           => throw new IllegalArgumentException(arg.mkString(" "))
      }
  }

  val adjusterConfig = parseArgs(args)

  val zipList = findZips(adjusterConfig.in)
  if (zipList.nonEmpty) {
    logger.info("Found {} zip files", zipList.size)
    logger.info("Transform to {}", adjusterConfig.out)
    if (Files.notExists(adjusterConfig.out))
      Files.createDirectories(adjusterConfig.out)
    zipList.foreach { zip =>
      val cfg = adjusterConfig.copy(in = zip, out = adjusterConfig.out.resolve(zip.getFileName))
      transformSingleEntry(cfg)
    }
  } else
    transformSingleEntry(adjusterConfig)

  private def findZips(dir: Path): List[Path] = {
    if (Files.isDirectory(dir))
      Files
        .walk(dir, 1)
        .filter(
          (file: Path) =>
            Files.isRegularFile(file)
            && "zip".equalsIgnoreCase(FilenameUtils.getExtension(file.getFileName.toString))
        )
        .sorted()
        .collect(Collectors.toList[Path])
        .asScala
        .toList
    else
      List()
  }

  private[transit] def transformSingleEntry(cfg: GtfsFeedAdjusterConfig) = {
    logger.info("Processing file {}, strategy: {}", cfg.in, cfg.strategy)
    val (trips, dao) = GtfsUtils.loadTripsFromGtfs(cfg.in)
    val strategy = cfg.strategy match {
      case "multiplication" if cfg.factor >= 1.0 =>
        GtfsUtils.doubleTripsStrategy(dao, trips, cfg.factor.toFloat, cfg.timeFrame)
      case "multiplication" if cfg.factor < 1.0 =>
        GtfsUtils.removeTripsStrategy(trips, cfg.factor.toFloat, cfg.timeFrame)
      case "scale" => GtfsUtils.scaleTripsStrategy(trips, cfg.factor.toInt, cfg.timeFrame)
    }
    GtfsUtils.transformGtfs(
      cfg.in,
      cfg.out,
      List(strategy)
    )
  }

}
