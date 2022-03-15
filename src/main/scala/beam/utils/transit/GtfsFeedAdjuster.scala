package beam.utils.transit

import java.nio.file.{Files, Path, Paths}
import java.util.stream.Collectors

import beam.utils.transit.GtfsUtils.TimeFrame
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
  *     '--op', 'multiplication|scale|remove_routes',
  *     '--factor', '0.5',
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
        case (config, Array("--op", "remove_routes")) =>
          config.copy(strategy = "remove_routes")
        case (config, Array("--op", "filter_service")) =>
          config.copy(strategy = "filter_service")
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
    zipList.foreach { zip =>
      val cfg = adjusterConfig.copy(in = zip, out = adjusterConfig.out.resolve(zip.getFileName))
      transformSingleEntry(cfg)
    }
  } else
    transformSingleEntry(adjusterConfig)


  private def findZips(dir: Path): List[Path] = {
    // todo load from file
    // already done
    val filesToTransform = Set(
      "MTA_Bronx_20200121.zip",
      "MTA_Brooklyn_20200118.zip",
      "MTA_Manhattan_20200123.zip",
      "MTA_Queens_20200118.zip",
      "MTA_Staten_Island_20200118.zip"
    )
    if (Files.isDirectory(dir))
      Files
        .walk(dir, 1)
        .filter((file: Path) =>
          Files.isRegularFile(file)
          && filesToTransform.contains(file.getFileName.toString)
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
    logger.info("Processing file {}, strategy: {}, factor {}", cfg.in, cfg.strategy, cfg.factor)
    if (Files.notExists(cfg.out.getParent)) {
      logger.info("Creating directory {}", cfg.out.getParent)
      Files.createDirectories(cfg.out.getParent)
    }
    //todo load from file
//    val modifiedRouteIds = Set(
//      "B15",
//      "M79+",
//      "M103",
//      "B82+",
//      "M7",
//      "M11",
//      "M5",
//      "B35",
//      "B41",
//      "B44",
//      "Q58",
//      "M1",
//      "M102",
//      "B8",
//      "M42",
//      "M31",
//      "M15+",
//      "B6",
//      "M86+",
//      "M15"
//    )
    val modifiedRouteIds: Set[String] = Set("1",  "10", "103", "105", "111", "135", "142",  "17", "171",  "18",   "2",  "20", "201",
  "214", "217", "228", "233", "237", "243", "271",   "3",  "30", "300", "310", "311", "315",
  "318", "322", "323", "324", "325", "333", "335", "337", "339", "345", "350", "383", "392",
  "4", "465", "466", "481", "483", "484", "485", "486",  "50",  "51", "152",   "6",   "7",
  "935", "980", "981", "982", "985", "987", "990", "150",  "19",   "5")
    // this is working
    val filteredServiceIds: Set[String] = Set("1-133_MRG_1")
    val (trips, dao) = GtfsUtils.loadTripsFromGtfs(cfg.in)
    val strategy = cfg.strategy match {
      case "multiplication" if cfg.factor >= 1.0 =>
        GtfsUtils.doubleTripsStrategy(dao, modifiedRouteIds, trips, cfg.factor.toFloat, cfg.timeFrame)
      case "multiplication" if cfg.factor < 1.0 && modifiedRouteIds.nonEmpty =>
        GtfsUtils.partiallyRemoveHalfTripsStrategy(trips, modifiedRouteIds, cfg.timeFrame)
      case "multiplication" if cfg.factor < 1.0 =>
        GtfsUtils.removeTripsStrategy(trips, cfg.factor.toFloat, cfg.timeFrame)
      case "remove_routes"  => GtfsUtils.removeRoutesStrategy(modifiedRouteIds)
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
