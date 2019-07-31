package beam.side.speed.parser
import java.io.{BufferedWriter, File, FileWriter}

import beam.side.speed.model.{BeamSpeed, BeamUberSpeed, LinkSpeed, WaySpeed}
import beam.side.speed.parser.data.Dictionary

class SpeedComparator(
  ways: OsmWays,
  uber: UberSpeed[_],
  beamDict: Dictionary[BeamSpeed, Long, BeamSpeed],
  fileName: String
) {
  import LinkSpeed._

  private def nodeCompare: Iterator[LinkSpeed] =
    ways.nodes
      .map(
        n =>
          uber
            .speed(n.id)
            .orElse(uber.way(n.orig, n.dest))
            .filter(_.speedMedian.exists(_ > 11))
            .collect {
              case WaySpeed(Some(speedMedian), _, Some(_)) if speedMedian >= n.speed =>
                LinkSpeed(n.eId, None, Some(speedMedian), None)
              case WaySpeed(Some(speedMedian), _, Some(points))
                  if points >= 10 && ((n.speed - speedMedian) / n.speed) > 0.3 =>
                LinkSpeed(n.eId, None, Some(speedMedian), None)
          }
      )
      .collect {
        case Some(b) => b
      }

  private def nodeCompareSimplified: Iterator[LinkSpeed] =
    ways.nodes
      .map(
        n =>
          uber
            .speed(n.id)
            .orElse(uber.waySimplified(n.orig, n.dest, beamDict))
            .collect {
              case WaySpeed(None, Some(sp), None) => LinkSpeed(n.eId, None, Some(sp), None)
          }
      )
      .collect {
        case Some(b) => b
      }

  private val csv: Iterator[LinkSpeed] => Unit = { s =>
    val file = new File(fileName)
    val bw = new BufferedWriter(new FileWriter(file))
    bw.write("link_id,capacity,free_speed,length")
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

  def apply(
    ways: OsmWays,
    uber: UberSpeed[_],
    beamDict: Dictionary[BeamSpeed, Long, BeamSpeed],
    fileName: String
  ): SpeedComparator =
    new SpeedComparator(ways, uber, beamDict, fileName)
}
