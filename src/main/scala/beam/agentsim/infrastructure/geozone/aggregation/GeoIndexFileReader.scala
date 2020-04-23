package beam.agentsim.infrastructure.geozone.aggregation

import java.nio.file.Path

import scala.io.Source

import beam.agentsim.infrastructure.geozone.GeoIndex
import beam.utils.FileUtils.using

object GeoIndexFileReader {

  def readIndexes(path: Path): Set[GeoIndex] = {
    using(Source.fromFile(path.toFile)) { source =>
      val allLines = source.getLines().toList
      allLines.flatMap(GeoIndex.tryCreate).toSet
    }
  }

}
