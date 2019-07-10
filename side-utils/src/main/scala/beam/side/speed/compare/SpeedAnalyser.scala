package beam.side.speed.compare

import java.io.{BufferedWriter, File, FileWriter}
import java.nio.file.Paths

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
        bw.write(b.productIterator.mkString(","))
        bw.newLine()
      }
    bw.flush()
    bw.close()
  }

  def nodePartsSpeed(): Unit = {
    ways.nodes
      .take(12)
      .map(n => n.id -> uber.wayParts(n.orig, n.dest))
      .collect {
        case (l, Some(s)) => l.toString -> s
      }
      .foreach { b =>
        Option(Paths.get(s"$filePrefix-${b._1}.csv").toFile)
          .filter(f => !f.exists())
          .foreach { f =>
            val bw = new BufferedWriter(new FileWriter(f))
            b._2.foreach { s =>
              bw.write(s.mkString(","))
              bw.newLine()
            }
            bw.flush()
            bw.close()
          }
      }
  }
}

object SpeedAnalyser {

  def apply(ways: OsmWays, uber: UberSpeed[_], filePrefix: String): SpeedAnalyser =
    new SpeedAnalyser(ways, uber, filePrefix)
}
