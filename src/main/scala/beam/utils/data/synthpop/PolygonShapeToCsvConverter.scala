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

import scala.util.{Failure, Success, Try}

/**
  * @author Dmitry Openkov
  */
object PolygonShapeToCsvConverter extends App with StrictLogging {

  private val crsCode = "epsg:26910"
  private val idAttributeName = "blkgrpid"
//  private val input = "/home/dimao/Downloads/block_shp/641aa0d4-ce5b-4a81-9c30-8790c4ab8cfb202047-1-wkkklf.j5ouj.shp"
  private val input = "/home/dimao/Downloads/block_shp/sf-light2.shp"
  private val output = "/home/dimao/Downloads/block_shp/block_group-centers.csv.gz"

  private val geoUtils: GeoUtils = SimpleGeoUtils(localCRS = crsCode)

  private def mapper(mathTransform: MathTransform, feature: SimpleFeature): (String, Geometry) = {
    val geoId = feature.getAttribute(idAttributeName).toString
    val geom = PreparedGeometryFactory.prepare(feature.getDefaultGeometry.asInstanceOf[Geometry])
    val wgsGeom = JTS.transform(geom.getGeometry, mathTransform)
    geoId -> wgsGeom
  }

  logger.info("Loading shape file {}", input)
  private val idToGeom: Array[(String, Geometry)] = ShapefileReader.read(crsCode, input, _ => true, mapper)
  logger.info("Loaded {} geo objects", idToGeom.length)

  logger.info("Write centroids to {}", output)
  writeCenters(output) match {
    case Failure(exception) =>
      logger.error("Cannot write to {}", output, exception)
    case Success(_) =>
      logger.info("Successfully written to {}", output)
  }

  def writeCenters(path: String): Try[Unit] = {
    val csvWriter = new CsvWriter(path, Array("taz", "coord-x", "coord-y", "area"))
    val rows = idToGeom.view.map {
      case (geoId, geo) =>
        val utmCoord = geoUtils.wgs2Utm(new Coord(geo.getCentroid.getX, geo.getCentroid.getY))
        Seq(geoId, utmCoord.getX, utmCoord.getY, geo.getArea)
    }
    csvWriter.writeAllAndClose(rows)
  }
}
