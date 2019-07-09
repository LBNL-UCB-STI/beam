package beam.side.speed.parser
import java.io.{BufferedWriter, File, FileWriter}

import beam.side.speed.model.{BeamUberSpeed, OsmNodeSpeed}

import scala.collection.SortedMap
import scala.collection.immutable.TreeMap

class SpeedComparator(ways: OsmWays, uber: UberSpeed[_], fileName: String) {
  import BeamUberSpeed._

  private def nodeCompare: Set[BeamUberSpeed] =
    SortedMap[(Long, Boolean), OsmNodeSpeed](ways.nodes: _*)
      .map(
        n =>
          uber
            .speed(n.id)
            .orElse(uber.way(n.orig, n.dest))
            .fold(BeamUberSpeed(n.id, n.speed, None, None, None))(
              ws => BeamUberSpeed(n.id, n.speed, ws.speedMedian, ws.speedAvg, ws.maxDev)
          )
      )

  def nodeParts(): Unit = {
    val file = new File(fileName)
    val bw = new BufferedWriter(new FileWriter(file))
    ways.nodes
      .map(n => n.id -> uber.wayParts(n.orig, n.dest))
      .collect {
        case (l, Some(s)) => l.toString -> s
      }
      .foreach { b =>
        bw.newLine()
        bw.write(b.productIterator.mkString(","))
      }
    bw.flush()
    bw.close()
  }

  private val csv: Set[BeamUberSpeed] => Unit = { s =>
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
