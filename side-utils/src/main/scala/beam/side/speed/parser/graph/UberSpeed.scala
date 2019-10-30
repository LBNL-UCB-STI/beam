package beam.side.speed.parser.graph

import beam.side.speed.model._
import beam.side.speed.parser.operation.Wrapper._
import beam.side.speed.parser.operation.{
  ObservationComposer,
  ObservationFilter,
  Wrapper,
  SpeedDataExtractor
}

class UberSpeed[F[_]: SpeedDataExtractor: Wrapper] {

  def speed[T <: FilterEventAction](osmId: Long)(
      implicit composer: ObservationComposer[F, Seq[WayMetric], UberWaySpeed],
      filter: ObservationFilter[F, T]
  ): F[T#FilterEvent] =
    for {
      path <- SpeedDataExtractor[F].speed(osmId)
      filtered <- composeFilter(path)
    } yield filtered

  def way[T <: FilterEventAction](origNodeId: Long, destNodeId: Long)(
      implicit composer: ObservationComposer[F, Seq[WayMetrics], UberWaySpeed],
      filter: ObservationFilter[F, T]
  ): F[T#FilterEvent] =
    for {
      path <- SpeedDataExtractor[F].speed(origNodeId, destNodeId)
      filtered <- composeFilter(path)
    } yield filtered

  private def composeFilter[R, T <: FilterEventAction](raw: R)(
      implicit composer: ObservationComposer[F, R, UberWaySpeed],
      filter: ObservationFilter[F, T]
  ): F[T#FilterEvent] =
    for {
      speed <- ObservationComposer[R, UberWaySpeed, F].compose(raw)
      filtered <- ObservationFilter[F, T].filter(speed)
    } yield filtered
}

object UberSpeed {

  def apply[F[_]: SpeedDataExtractor: Wrapper](
      implicit Speed: UberSpeed[F]): UberSpeed[F] = Speed
}
