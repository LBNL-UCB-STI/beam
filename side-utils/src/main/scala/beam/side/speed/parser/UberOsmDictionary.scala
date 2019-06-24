package beam.side.speed.parser
import java.nio.file.{Path, Paths}

import beam.side.speed.model.UberOsmWays

class UberOsmDictionary(waysPath: Path) extends DataLoader[UberOsmWays] with UnarchivedSource {
  private val osmDictionary: Map[Long, String] = load(waysPath).map(w => w.osmWayId -> w.segmentId).toMap

  def apply(osmWayId: Long): Option[String] = osmDictionary.get(osmWayId)
}

object UberOsmDictionary {
  def apply(waysPath: String): UberOsmDictionary = new UberOsmDictionary(Paths.get(waysPath))
}
