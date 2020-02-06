package beam.taz
import census.db.creator.database.TazRepository
import census.db.creator.domain.TazInfo
import com.vividsolutions.jts.geom.Coordinate

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

    (0 until number).map(_ => taz.geometry.getCentroid.getCoordinate)
  }
}
