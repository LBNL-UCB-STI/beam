package scripts.shapefiles

import beam.agentsim.infrastructure.taz.CsvTaz
import beam.utils.csv.CsvWriter
import beam.utils.map.ShapefileReader
import com.typesafe.scalalogging.StrictLogging
import com.vividsolutions.jts.geom.{Geometry, MultiPolygon, Polygon, Polygonal}
import com.vividsolutions.jts.geom.prep.PreparedGeometryFactory
import org.geotools.geometry.jts.JTS
import org.matsim.core.utils.geometry.geotools.MGC
import org.opengis.feature.simple.SimpleFeature
import org.opengis.referencing.operation.MathTransform

import java.io.File
import java.nio.file.{Path, Paths}
import scala.util.{Failure, Success, Try}

/**
  * Do mapping of taz ids to the polygons in the shape file.
  * If the TAZ center is within a polygon then that TAZ belongs to that polygon.
  * Each set of TAZ ids is saved as a separate csv file (named {feature-id}.csv) with a single column (taz).
  * You can run it using
  * {{{
  * ./gradlew :execute -PmainClass=scripts.shapefiles.PolygonShapesToTazIdsMapping \
  *  -PappArgs="['--shape-file=test/input/sf-light/shape/sf-light-split.shp', '--taz-file=test/input/sf-light/taz-centers.csv', '--output-dir=test/input/sf-light/geofence', '--crs=epsg:26910', '--id-attribute-name=id']"
  * }}}
  *
  * @author Dmitry Openkov
  */
object PolygonShapesToTazIdsMapping extends App with StrictLogging {

  parseArgs(args) match {
    case Some(cliOptions) =>
      doJob(
        cliOptions.shapeFile,
        cliOptions.tazFile,
        cliOptions.outputDir,
        cliOptions.crsCode,
        cliOptions.idAttributeName
      )
    case None => System.exit(1)
  }

  private def doJob(shapeFile: Path, tazFile: Path, outputDir: Path, crsCode: String, idAttributeName: String): Unit = {
    def mapper(mathTransform: MathTransform, feature: SimpleFeature): (String, Geometry) = {
      val geoId = feature.getAttribute(idAttributeName).toString
      val geom = PreparedGeometryFactory.prepare(feature.getDefaultGeometry.asInstanceOf[Geometry])
      val wgsGeom = JTS.transform(geom.getGeometry, mathTransform)
      geoId -> wgsGeom
    }

    def filter(feature: SimpleFeature): Boolean = feature.getAttribute(idAttributeName) != null

    logger.info("Loading shape file {}", shapeFile)
    val polygons: Array[(String, Geometry)] = ShapefileReader.read(crsCode, shapeFile.toString, filter, mapper)
    logger.info("Loaded {} geo objects", polygons.length)

    val tazes: Set[CsvTaz] = CsvTaz.readCsvFile(tazFile.toString).toSet
    logger.info("Loaded {} TAZes", tazes.size)

    def findContainingPolygonId(taz: CsvTaz): Option[String] = {
      polygons.collectFirst {
        case (polygonId, polygon) if polygon.contains(MGC.xy2Point(taz.coordX, taz.coordY)) => polygonId
      }
    }

    val polygonToTazMapping: Map[Option[String], Set[CsvTaz]] = tazes.groupBy(findContainingPolygonId)
    logger.info("Number of not mapped TAZes: {}", polygonToTazMapping.get(None).fold(0)(_.size))

    def writeTazes(tazes: Set[CsvTaz], path: Path): Try[Unit] = {
      val csvWriter = new CsvWriter(path.toString, Array("taz"))
      csvWriter.writeAllAndClose(tazes.map(taz => Seq(taz.id)))
    }

    logger.info("Write taz csv files to {}", outputDir)
    val writeResult = polygonToTazMapping.collect {
      case (Some(polygonId), tazes) if tazes.nonEmpty =>
        writeTazes(tazes, outputDir.resolve(s"tazes_$polygonId.csv"))
    }.toVector
    import cats.implicits._
    writeResult.sequence match {
      case Failure(exception) =>
        logger.error("Cannot write to {}", outputDir, exception)
      case Success(result) =>
        logger.info("Successfully written {} files to {}", result.size, outputDir)
    }
  }

  case class CliOptions(
    crsCode: String,
    idAttributeName: String,
    shapeFile: Path,
    tazFile: Path,
    outputDir: Path
  )

  private def parseArgs(args: Array[String]): Option[CliOptions] = {
    import scopt.OParser
    val builder = OParser.builder[CliOptions]
    val parser1 = {
      import builder._
      OParser.sequence(
        programName("shp-to-csv-converter"),
        head("This program converts a shape file to a csv that contains centroids fo polygons with their ids"),
        opt[File]('s', "shape-file")
          .required()
          .valueName("<shape-file>")
          .action((x, c) => c.copy(shapeFile = x.toPath))
          .text("File containing shape with polygons (shp file)"),
        opt[File]('t', "taz-file")
          .required()
          .valueName("<taz-file>")
          .action((x, c) => c.copy(tazFile = x.toPath))
          .text("File containing taz centers"),
        opt[File]('o', "output-dir")
          .required()
          .valueName("<output-dir>")
          .action((x, c) => c.copy(outputDir = x.toPath))
          .text("path where to save the generated taz files"),
        opt[String]('c', "crs")
          .required()
          .action((x, c) => c.copy(crsCode = x))
          .text("CRS code (i.e. epsg:26910)"),
        opt[String]('a', "id-attribute-name")
          .required()
          .action((x, c) => c.copy(idAttributeName = x))
          .text("the name of id attribute of the polygons"),
        help("help")
      )
    }
    OParser.parse(parser1, args, CliOptions("", "", Paths.get("."), Paths.get("."), Paths.get(".")))
  }
}
