package census.db.creator.domain
import com.vividsolutions.jts.geom.Geometry

case class DataRow(
  geoId: String,
  stateFp: String,
  countyFp: String,
  tractCode: String,
  landArea: Long,
  waterArea: Long,
  geometry: Geometry
)
