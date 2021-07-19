package beam.utils.data.synthpop

import beam.sim.common.{GeoUtils, SimpleGeoUtils}
import beam.utils.csv.CsvWriter
import beam.utils.map.ShapefileReader
import com.typesafe.scalalogging.StrictLogging
import com.vividsolutions.jts.geom.Geometry
import com.vividsolutions.jts.geom.prep.PreparedGeometryFactory
import org.geotools.geometry.jts.JTS
import org.matsim.api.core.v01.Coord
import org.opengis.feature.simple.SimpleFeature
import org.opengis.referencing.operation.MathTransform

import java.io.File
import java.nio.file.{Path, Paths}
import scala.util.{Failure, Success, Try}

/**
  * Converts shp with polygons to csv similar to taz-centers.csv
  * You can run it using
  * {{{
  * ./gradlew :execute -PmainClass=beam.utils.data.synthpop.PolygonShapeToCsvConverter \
  *  -PappArgs="['--input=./j5ouj.shp', '--output=./block_group-centers.csv.gz', '--crs=epsg:26910', '--id-attribute-name=blkgrpid']"
  * }}}
  * @author Dmitry Openkov
  */
object PolygonShapeToCsvConverter extends App with StrictLogging {

  parseArgs(args) match {
    case Some(cliOptions) =>
      doJob(cliOptions.input, cliOptions.output, cliOptions.crsCode, cliOptions.idAttributeName)
    case None => System.exit(1)
  }

  private def doJob(input: Path, output: Path, crsCode: String, idAttributeName: String): Unit = {
    def mapper(mathTransform: MathTransform, feature: SimpleFeature): (String, Geometry) = {
      val geoId = feature.getAttribute(idAttributeName).toString
      val geom = PreparedGeometryFactory.prepare(feature.getDefaultGeometry.asInstanceOf[Geometry])
      val wgsGeom = JTS.transform(geom.getGeometry, mathTransform)
      geoId -> wgsGeom
    }

    logger.info("Loading shape file {}", input)
    val idToGeom: Array[(String, Geometry)] = ShapefileReader.read(crsCode, input.toString, _ => true, mapper)
    logger.info("Loaded {} geo objects", idToGeom.length)

    val geoUtils: GeoUtils = SimpleGeoUtils(localCRS = crsCode)

    def writeCenters(path: Path): Try[Unit] = {
      val csvWriter = new CsvWriter(path.toString, Array("taz", "coord-x", "coord-y", "area"))
      val rows = idToGeom.view.map { case (geoId, geo) =>
        val utmCoord = geoUtils.wgs2Utm(new Coord(geo.getCentroid.getX, geo.getCentroid.getY))
        Seq(geoId, utmCoord.getX, utmCoord.getY, geo.getArea)
      }
      csvWriter.writeAllAndClose(rows)
    }

    logger.info("Write centroids to {}", output)
    writeCenters(output) match {
      case Failure(exception) =>
        logger.error("Cannot write to {}", output, exception)
      case Success(_) =>
        logger.info("Successfully written to {}", output)
    }
  }

  case class CliOptions(
    crsCode: String,
    idAttributeName: String,
    input: Path,
    output: Path
  )

  private def parseArgs(args: Array[String]): Option[CliOptions] = {
    import scopt.OParser
    val builder = OParser.builder[CliOptions]
    val parser1 = {
      import builder._
      OParser.sequence(
        programName("shp-to-csv-converter"),
        head("This program converts an shp file to a csv that contains centroids fo polygons with their ids"),
        opt[File]('i', "input")
          .required()
          .valueName("<file>")
          .action((x, c) => c.copy(input = x.toPath))
          .text("File containing shape with polygons (shp file)"),
        opt[File]('o', "output")
          .required()
          .valueName("<file>")
          .action((x, c) => c.copy(output = x.toPath))
          .text("path where to save the generated taz-centers file"),
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
    OParser.parse(parser1, args, CliOptions("", "", Paths.get("."), Paths.get(".")))
  }
}
