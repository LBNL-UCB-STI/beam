package census.db.creator
import com.vividsolutions.jts.geom._
import com.vividsolutions.jts.io.{WKBReader, WKTReader}

object GeometryUtil {
  val projection = 4326
  val geometryFactory = new GeometryFactory(new PrecisionModel(), projection)

  private val wktReader = new WKTReader(geometryFactory)
  private val wkbReader = new WKBReader(geometryFactory)

  def readWkt(wkt: String): Geometry = wktReader.read(wkt)
  def readPolygonFromWkt(wkt: String): Polygon = wktReader.read(wkt).asInstanceOf[Polygon]
  def readWkb(wkb: Any): Geometry = wkbReader.read(wkb.asInstanceOf[Array[Byte]])

  def envelopeToPolygon(env: Envelope) =
    geometryFactory.createPolygon(
      Array(
        new Coordinate(env.getMinX, env.getMinY),
        new Coordinate(env.getMinX, env.getMaxY),
        new Coordinate(env.getMaxX, env.getMaxY),
        new Coordinate(env.getMaxX, env.getMinY),
        new Coordinate(env.getMinX, env.getMinY)
      )
    )

}
