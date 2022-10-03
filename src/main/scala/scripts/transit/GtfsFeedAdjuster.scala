package scripts.transit

import com.typesafe.scalalogging.StrictLogging
import org.apache.commons.io.FilenameUtils
import org.onebusaway.gtfs.services.GtfsMutableRelationalDao
import org.onebusaway.gtfs_transformer.services.{GtfsTransformStrategy, TransformContext}
import scripts.transit.GtfsUtils.TimeFrame

import java.nio.file.{Files, Path, Paths}
import java.util.stream.Collectors
import scala.collection.JavaConverters._

/**
  * Run directly from CLI with, for example:
  * {{{
  *   ./gradlew execute -PmainClass=scripts.transit.GtfsFeedAdjuster -PappArgs=\
  *   "[
  *     '--op', 'multiplication|scale|remove_routes',
  *     '--factor', '0.5',
  *     '--startTime', '36000',
  *     '--endTime', '57600',
  *     '--in', 'single GTFS zip archive OR folder',
  *     '--out', 'test/input/sf-light/r5/BA-out.zip',
  *      OPTIONAL '--filesToTransform', 'MTA_Bronx_20200121.zip,MTA_Brooklyn_20200118.zip',
  *      OPTIONAL '--routesToModify', 'B15,M79+,M103,B82+'
  *   ]"
  * }}}
  */
object GtfsFeedAdjuster extends App with StrictLogging {

  final case class GtfsFeedAdjusterConfig(
    strategy: String = "multiplication",
    factor: Double = 1.0,
    timeFrame: TimeFrame = TimeFrame.WholeDay,
    in: Path = Paths.get("."),
    out: Path = Paths.get("."),
    filesToTransform: Set[String] = Set.empty[String],
    routesToModify: Set[String] = Set.empty[String]
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
        case (config, Array("--op", "remove_routes")) =>
          config.copy(strategy = "remove_routes")
        case (config, Array("--op", "filter_service")) =>
          config.copy(strategy = "filter_service")
        case (config, Array("--factor", s))           => config.copy(factor = s.toDouble)
        case (config, Array("--in", s))               => config.copy(in = Paths.get(s))
        case (config, Array("--out", s))              => config.copy(out = Paths.get(s))
        case (config, Array("--startTime", s))        => config.copy(timeFrame = config.timeFrame.copy(startTime = s.toInt))
        case (config, Array("--endTime", s))          => config.copy(timeFrame = config.timeFrame.copy(endTime = s.toInt))
        case (config, Array("--filesToTransform", s)) => config.copy(filesToTransform = s.split(",").toSet)
        case (config, Array("--routesToModify", s))   => config.copy(routesToModify = s.split(",").toSet)
        case (_, arg)                                 => throw new IllegalArgumentException(arg.mkString(" "))
      }
  }

  val adjusterConfig = parseArgs(args)

  val zipList = findZips(adjusterConfig.in, adjusterConfig.filesToTransform)
  if (zipList.nonEmpty) {
    logger.info("Found {} zip files", zipList.size)
    logger.info("Transform to {}", adjusterConfig.out)
    zipList.foreach { zip =>
      val cfg = adjusterConfig.copy(in = zip, out = adjusterConfig.out.resolve(zip.getFileName))
      transformSingleEntry(cfg)
    }
  } else
    transformSingleEntry(adjusterConfig)

  private def findZips(dir: Path, filesToTransform: Set[String]): List[Path] = {
    def filePathIsSelected(file: Path): Boolean =
      Files.isRegularFile(file) &&
      (filesToTransform.isEmpty || filesToTransform.contains(file.getFileName.toString)) &&
      "zip".equalsIgnoreCase(FilenameUtils.getExtension(file.getFileName.toString))

    if (Files.isDirectory(dir))
      Files
        .walk(dir, 1)
        .filter((file: Path) => filePathIsSelected(file))
        .sorted()
        .collect(Collectors.toList[Path])
        .asScala
        .toList
    else
      List()
  }

  private[transit] def transformSingleEntry(cfg: GtfsFeedAdjusterConfig) = {
    logger.info("Processing file {}, strategy: {}, factor {}", cfg.in, cfg.strategy, cfg.factor)
    if (Files.notExists(cfg.out.getParent)) {
      logger.info("Creating directory {}", cfg.out.getParent)
      Files.createDirectories(cfg.out.getParent)
    }

    val filteredServiceIds: Set[String] = Set("MRG_1", "39101-133")
    val (trips, dao) = GtfsUtils.loadTripsFromGtfs(cfg.in)
    val strategy = cfg.strategy match {
      case "multiplication" if cfg.factor >= 1.0 =>
        GtfsUtils.doubleTripsStrategy(dao, cfg.routesToModify, trips, cfg.factor.toFloat, cfg.timeFrame)
      case "multiplication" if cfg.factor < 1.0 && cfg.routesToModify.nonEmpty =>
        GtfsUtils.partiallyRemoveHalfTripsStrategy(trips, cfg.routesToModify, cfg.timeFrame)
      case "multiplication" if cfg.factor < 1.0 =>
        GtfsUtils.removeTripsStrategy(trips, cfg.factor.toFloat, cfg.timeFrame)
      case "remove_routes"  => GtfsUtils.removeRoutesStrategy(cfg.routesToModify)
      case "scale"          => GtfsUtils.scaleTripsStrategy(trips, cfg.factor.toInt, cfg.timeFrame)
      case "filter_service" => GtfsUtils.filterServiceIdStrategy(filteredServiceIds)
    }

    GtfsUtils.transformGtfs(
      cfg.in,
      cfg.out,
      List(strategy)
    )
  }

}
