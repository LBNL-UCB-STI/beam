package beam.taz
import census.db.creator.GeometryUtil
import census.db.creator.database.TazInfoRepoImpl
import com.conveyal.osmlib.OSM
import com.vividsolutions.jts.geom.Envelope

object TazQueryApp extends App {
  require(args.length == 1, "PBF path should be specified")

  val pbf = args(0)
  val osm = new OSM("/tmp/osm/")
//  osm.readFromFile(pbf)

  var minX = Double.MaxValue
  var maxX = Double.MinValue
  var minY = Double.MaxValue
  var maxY = Double.MinValue
  osm.nodes.values().forEach { x =>
    val lon = x.fixedLon / 10000000.0
    val lat = x.fixedLat / 10000000.0

    if (lon < minX) minX = lon
    if (lon > maxY) maxX = lon
    if (lat < minY) minY = lat
    if (lat < minX) maxY = lat
  }

  val envelope = GeometryUtil.envelopeToPolygon(new Envelope(minX, maxX, minY, maxY))

  val tazRepo = new TazInfoRepoImpl()

  val tazesInsideOfBoundingBox = tazRepo.query(border = Some(envelope))
}
