package census.db.creator.domain
import com.vividsolutions.jts.geom.prep.PreparedGeometry

case class TazInfo(
  geoId: String,
  stateFp: String,
  countyFp: String,
  tractCode: String,
  landArea: Long,
  waterArea: Long,
  preparedGeometry: PreparedGeometry
)
