package census.db.creator
import com.vividsolutions.jts.geom._
import com.vividsolutions.jts.io.{WKBReader, WKTReader}

object GeometryUtil {
  val projection = 4326
  val geometryFactory = new GeometryFactory(new PrecisionModel(), projection)

  private val wktReader = new WKTReader(geometryFactory)
  private val wkbReader = new WKBReader(geometryFactory)

  def readWkt(wkt: String): Geometry = wktReader.read(wkt)
  def readPolygonFromWkt(wkt: String): Geometry = wktReader.read(wkt).asInstanceOf[Polygon]
  def readWkb(wkb: Any): Geometry = wkbReader.read(wkb.asInstanceOf[Array[Byte]])

  def envelopeToPolygon(env: Envelope) = {
    val coords = new Array[Coordinate](5)
    coords(0) = new Coordinate(env.getMinX, env.getMinY)
    coords(1) = new Coordinate(env.getMinX, env.getMaxY)
    coords(2) = new Coordinate(env.getMaxX, env.getMaxY)
    coords(3) = new Coordinate(env.getMaxX, env.getMinY)
    coords(4) = new Coordinate(env.getMinX, env.getMinY)
    geometryFactory.createPolygon(coords)
  }

}
