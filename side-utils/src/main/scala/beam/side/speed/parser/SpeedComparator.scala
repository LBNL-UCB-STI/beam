package beam.side.speed.parser
import java.io.{BufferedWriter, File, FileWriter}

import beam.side.speed.model._
import beam.side.speed.parser.data.Dictionary
import beam.side.speed.parser.graph.UberSpeed
import beam.side.speed.parser.operation.{ObservationComposer, ObservationFilter, Program}

class SpeedComparator[T <: FilterEventAction, F[_]: Program](ways: OsmWays, uber: UberSpeed[F], fileName: String)(
  implicit composerMetric: ObservationComposer[F, Seq[WayMetric], UberWaySpeed],
  composerMetrics: ObservationComposer[F, Seq[WayMetrics], UberWaySpeed],
  filter: ObservationFilter[F, T]
) {
  import LinkSpeed._

  /*private def node[R <: Product](osmNodeSpeed: OsmNodeSpeed): Iterator[R] =
    for {

    }

  private def nodeCompare: Iterator[BeamSpeed] =
    ways.nodes
      .map(
        n =>
          uber
            .speed(n.id)
            .orElse(uber.way(n.orig, n.dest))
            .filter(_.speedMedian.exists(_ > 11))
            .collect {
              case WaySpeed(Some(speedMedian), _, Some(_)) if speedMedian >= n.speed =>
                //LinkSpeed(n.eId, None, Some(speedMedian), None)
                BeamSpeed(n.id, speedMedian, n.lenght)
              case WaySpeed(Some(speedMedian), _, Some(points))
                  if points >= 10 && ((n.speed - speedMedian) / n.speed) > 0.3 =>
                //LinkSpeed(n.eId, None, Some(speedMedian), None)
                BeamSpeed(n.id, speedMedian, n.lenght)
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
      }*/

  private def nodeCompareSimplified: Iterator[LinkSpeed] = ???

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

  def csvNode(): Unit = csv(nodeCompareSimplified)
}

object SpeedComparator {

  def apply[T <: FilterEventAction, F[_]: Program](ways: OsmWays, uber: UberSpeed[F], fileName: String)(
    implicit composerMetric: ObservationComposer[F, Seq[WayMetric], UberWaySpeed],
    composerMetrics: ObservationComposer[F, Seq[WayMetrics], UberWaySpeed],
    filter: ObservationFilter[F, T]
  ): SpeedComparator[T, F] = new SpeedComparator(ways, uber, fileName)
}
