package beam.taz
import census.db.creator.GeometryUtil
import census.db.creator.database.TazRepository
import census.db.creator.domain.TazInfo
import com.vividsolutions.jts.geom.{Coordinate, Envelope, Geometry, TopologyException}

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

    @tailrec def divide(pieces: Int, splitFeatures: Seq[Geometry]): Seq[Geometry] = {

      @tailrec def safeIntersection(g1: Geometry, g2: Geometry, buffer: Double = 0.0): Geometry = {
        try {
          g1.intersection(g2)
        } catch {
          case _: TopologyException =>
            safeIntersection(g1.buffer(buffer), g2.buffer(buffer), buffer + 1.0)
        }
      }

      if (splitFeatures.size >= pieces) return splitFeatures.take(number)
      val g = splitFeatures.head
      val bb = g.getEnvelopeInternal
      val newGeometries = Seq(
        new Envelope(bb.getMinX, bb.centre().x, bb.getMinY, bb.centre().y),
        new Envelope(bb.centre().x, bb.getMaxX, bb.getMinY, bb.centre().y),
        new Envelope(bb.getMinX, bb.centre().x, bb.centre().y, bb.getMaxY),
        new Envelope(bb.centre().x, bb.getMaxX, bb.centre().y, bb.getMaxY)
      ).map(GeometryUtil.envelopeToPolygon)
        .map(e => safeIntersection(g, e))
        .filter(!_.isEmpty)

      divide(number, splitFeatures.drop(1) ++ newGeometries)
    }

    divide(number, Seq(taz.geometry)).map(_.getCentroid.getCoordinate)
  }
}
