package census.db.creator
import com.vividsolutions.jts.geom.{Geometry, GeometryFactory, PrecisionModel}
import com.vividsolutions.jts.io.{WKBReader, WKTReader}

private[creator] object GeometryUtil {
  val projection = 4326

  private val wktReader = new WKTReader(new GeometryFactory(new PrecisionModel(), projection))
  private val wkbReader = new WKBReader(new GeometryFactory(new PrecisionModel(), projection))

  def readWkt(wkt: String): Geometry = wktReader.read(wkt)
  def readWkb(wkb: Any): Geometry = wkbReader.read(wkb.asInstanceOf[Array[Byte]])

}
