package beam.side.speed.parser
import java.io.{BufferedWriter, File, FileWriter}

import beam.side.speed.model.{BeamUberSpeed, LinkSpeed}

class SpeedComparator(ways: OsmWays, uber: UberSpeed[_], fileName: String) {
  import LinkSpeed._

  private def nodeCompare: Iterator[LinkSpeed] =
    ways.nodes
      .map(
        n =>
          uber
            .speed(n.id)
            .orElse(uber.way(n.orig, n.dest))
            .map(
              ws =>
                LinkSpeed(n.eId, Some(n.speed), ws.speedAvg, ws.speedAvg.map(s => n.speed - s), ws.maxDev.map(_.toInt))
          )
      )
      .collect {
        case Some(b) => b
      }

  private val csv: Iterator[LinkSpeed] => Unit = { s =>
    val file = new File(fileName)
    val bw = new BufferedWriter(new FileWriter(file))
    bw.write("link_id,beam_speed,free_speed,diff")
    s.foreach { b =>
      bw.newLine()
      bw.write(linkSpeedSpeedEncoder(b))
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
