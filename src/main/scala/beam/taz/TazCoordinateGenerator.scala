package beam.taz
import census.db.creator.GeometryUtil
import census.db.creator.database.TazRepository
import census.db.creator.domain.TazInfo
import com.vividsolutions.jts.geom._
import com.vividsolutions.jts.shape.random.RandomPointsInGridBuilder

import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap

trait TazCoordinateGenerator {
  def generate(geoId: String, number: Int): Seq[Coordinate]
}

class TazCoordinateGeneratorImpl(private val osmService: OsmService, private val tazRepo: TazRepository)
    extends TazCoordinateGenerator {
  private val tazCache = TrieMap.empty[String, TazInfo]

  override def generate(geoId: String, number: Int): Seq[Coordinate] = {
    val taz = tazCache.getOrElseUpdate(geoId, {
      val tazs = tazRepo.query(Some(geoId))
      require(tazs.size == 1, { s"Failed to find TAZ for geoid $geoId" })
      tazs.head
    })

    val pointsBuilder = new RandomPointsInGridBuilder(GeometryUtil.geometryFactory)
    pointsBuilder.setExtent(taz.preparedGeometry.getGeometry.getEnvelopeInternal)

    @tailrec def generate(n: Int): Seq[Coordinate] = {
      pointsBuilder.setNumPoints(n)
      val multiPoint = pointsBuilder.getGeometry
      val points = (0 until multiPoint.getNumGeometries)
        .map(multiPoint.getGeometryN)
        .filter(taz.preparedGeometry.contains)
        .map(_.getCoordinate)

      if (points.size >= number) return points.take(number)

      generate((n * 1.1).toInt)
    }

    generate((number * 1.1).toInt)
  }
}
