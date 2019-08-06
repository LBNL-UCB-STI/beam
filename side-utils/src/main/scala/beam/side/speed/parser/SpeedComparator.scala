package beam.side.speed.parser
import beam.side.speed.model._
import beam.side.speed.parser.graph.UberSpeed
import beam.side.speed.parser.operation.Wrapper._
import beam.side.speed.parser.operation.{ObservationComposer, ObservationFilter, Wrapper, SpeedWriter}

class SpeedComparator[R <: Product, T <: FilterEventAction, F[_]: Wrapper](
  ways: OsmWays,
  uber: UberSpeed[F]
)(
  implicit composerMetric: ObservationComposer[F, Seq[WayMetric], UberWaySpeed],
  composerMetrics: ObservationComposer[F, Seq[WayMetrics], UberWaySpeed],
  composerNode: ObservationComposer[F, (OsmNodeSpeed, T#FilterEvent), R],
  filter: ObservationFilter[F, T],
  writer: SpeedWriter[R, F]
) {

  private def node(osmNodeSpeed: OsmNodeSpeed): F[R] =
    for {
      speed <- uber.speed(osmNodeSpeed.id).zip(uber.way(osmNodeSpeed.orig, osmNodeSpeed.dest))
      way   <- composerNode.compose((osmNodeSpeed, speed))
      _     <- writer.write(way)
    } yield way

  def compare(): F[Unit] = {
    ways.nodes.foreach(node)
    writer.flush()
  }
}

object SpeedComparator {

  def apply[R <: Product, T <: FilterEventAction, F[_]: Wrapper](
    implicit Comparator: SpeedComparator[R, T, F]
  ): SpeedComparator[R, T, F] = Comparator
}
