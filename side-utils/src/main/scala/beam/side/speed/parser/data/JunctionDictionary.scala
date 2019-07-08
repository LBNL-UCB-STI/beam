package beam.side.speed.parser.data

import java.nio.file.{Path, Paths}

import beam.side.speed.model.UberOsmNode

class JunctionDictionary(junctionsPath: Path) extends DataLoader[UberOsmNode] with UnarchivedSource {
  private val dict: Map[String, Long] = load(junctionsPath).map(w => w.segmentId -> w.osmNodeId).toMap

  def apply(segmentId: String): Option[Long] = dict.get(segmentId)
}

object JunctionDictionary {
  def apply(junctionsPath: String): JunctionDictionary = new JunctionDictionary(Paths.get(junctionsPath))
}
