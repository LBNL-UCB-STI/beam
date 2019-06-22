package beam.side.speed.parser
import java.nio.file.{Path, Paths}

import beam.side.speed.model.UberOsmWays

class UberOsmDictionary(waysPath: Path) extends DataLoader[UberOsmWays] with UnarchivedSource {
  private val osmDictionary: Map[String, Long] = load(waysPath).map(w => w.segmentId -> w.osmWayId).toMap

  def apply(segmentId: String): Long = osmDictionary(segmentId)
}

object UberOsmDictionary {
  def apply(waysPath: String): UberOsmDictionary = new UberOsmDictionary(Paths.get(waysPath))
}
