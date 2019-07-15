package beam.side.speed.parser
import java.io.{BufferedWriter, File, FileWriter}

import beam.side.speed.model.BeamUberSpeed

class SpeedComparator(ways: OsmWays, uber: UberSpeed[_], fileName: String) {
  import BeamUberSpeed._

  private def nodeCompare: Iterator[BeamUberSpeed] =
    ways.nodes
      .map(
        n =>
          uber
            .speed(n.id)
            .orElse(uber.way(n.orig, n.dest))
            .map(ws => BeamUberSpeed(n.id, n.speed, ws.speedMedian, ws.speedAvg, ws.maxDev))
      )
      .collect {
        case Some(b) => b
      }

  private val csv: Iterator[BeamUberSpeed] => Unit = { s =>
    val file = new File(fileName)
    val bw = new BufferedWriter(new FileWriter(file))
    bw.write("osmId,speedBeam,speedMedian,speedAvg,maxDev")
    s.foreach { b =>
      bw.newLine()
      bw.write(beamUberSpeedEncoder(b))
    }
    bw.flush()
    bw.close()
  }

  def csvNode(): Unit = csv(nodeCompare)
}

object SpeedComparator {

  def apply(ways: OsmWays, uber: UberSpeed[_], fileName: String): SpeedComparator =
    new SpeedComparator(ways, uber, fileName)
}
