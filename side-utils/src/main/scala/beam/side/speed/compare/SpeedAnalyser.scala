package beam.side.speed.compare

import java.io.{BufferedWriter, File, FileWriter}

import beam.side.speed.parser.{OsmWays, UberSpeed}

class SpeedAnalyser(ways: OsmWays, uber: UberSpeed[_], filePrefix: String) {

  def nodePartsMax(): Unit = {
    val file = new File(filePrefix)
    val bw = new BufferedWriter(new FileWriter(file))
    ways.nodes
      .map(n => n.id -> uber.wayPartsMax(n.orig, n.dest))
      .collect {
        case (l, Some(s)) => l.toString -> s
      }
      .foreach { b =>
        bw.write(b._2)
        bw.newLine()
      }
    bw.flush()
    bw.close()
  }

  def nodePartsSpeed(): Unit = {
    ways.nodes
      .map(n => n.id -> uber.wayParts(n.orig, n.dest))
      .collect {
        case (l, Some(s)) => l.toString -> s
      }
      .filter(b => Seq("29", "59").contains(b._1))
      .take(2)
      .foreach { b =>
        val file = new File(s"$filePrefix-${b._1}.csv")
        val bw = new BufferedWriter(new FileWriter(file))
        bw.write("speed,hour")
        bw.newLine()
        b._2.foreach { s =>
          s.hours.foreach { h =>
            bw.write(s"${h.speedAvg},${h.hour}")
            bw.newLine()
          }
        }
        bw.flush()
        bw.close()
      }
  }
}

object SpeedAnalyser {

  def apply(ways: OsmWays, uber: UberSpeed[_], filePrefix: String): SpeedAnalyser =
    new SpeedAnalyser(ways, uber, filePrefix)
}
