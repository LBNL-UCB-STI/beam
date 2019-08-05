package beam.side.speed.parser.graph

import beam.side.speed.model._
import beam.side.speed.parser.operation.Program._
import beam.side.speed.parser.operation.{ObservationComposer, ObservationFilter, Program, SpeedDataExtractor}

class UberSpeed[F[_]: SpeedDataExtractor: Program] {

  def speed[T <: FilterEventAction](osmId: Long)(
    implicit composer: ObservationComposer[F, Seq[WayMetric], UberWaySpeed],
    filter: ObservationFilter[F, T]
  ): F[T#FilterEvent] =
    for {
      path     <- SpeedDataExtractor[F].speed(osmId)
      filtered <- composeFilter(path)
    } yield filtered

  def way[T <: FilterEventAction](origNodeId: Long, destNodeId: Long)(
    implicit composer: ObservationComposer[F, Seq[WayMetrics], UberWaySpeed],
    filter: ObservationFilter[F, T]
  ): F[T#FilterEvent] =
    for {
      path     <- SpeedDataExtractor[F].speed(origNodeId, destNodeId)
      filtered <- composeFilter(path)
    } yield filtered

  private def composeFilter[R, T <: FilterEventAction](raw: R)(
    implicit composer: ObservationComposer[F, R, UberWaySpeed],
    filter: ObservationFilter[F, T]
  ): F[T#FilterEvent] =
    for {
      speed    <- ObservationComposer[R, UberWaySpeed, F].compose(raw)
      filtered <- ObservationFilter[F, T].filter(speed)
    } yield filtered
}

object UberSpeed {

  def apply[F[_]: SpeedDataExtractor: Program](implicit Speed: UberSpeed[F]): UberSpeed[F] = Speed

  /*def apply[T <: FilterEventAction](
    path: Seq[String],
    dictW: Dictionary[UberOsmWays, Long, String],
    dictS: Dictionary[UberOsmWays, String, Long],
    dictJ: Dictionary[UberOsmNode, String, Long],
    fOpt: T#Filtered
  )(
    implicit t: ClassTag[T],
    wf: WayFilter[T#FilterEvent, T#Filtered]
  ): UberSpeed[T] =
    new UberSpeed(path, dictW, dictS, dictJ, fOpt)*/

  /*def apply(
    mode: String,
    fOpt: Map[String, String],
    path: Seq[String],
    dictW: Dictionary[UberOsmWays, Long, String],
    dictS: Dictionary[UberOsmWays, String, Long],
    dictJ: Dictionary[UberOsmNode, String, Long]
  ): UberSpeed[_] = mode match {
    case "all"   => UberSpeed[AllHoursDaysEventAction](path, dictW, dictS, dictJ, Unit)
    case "wd"    => UberSpeed[WeekDayEventAction](path, dictW, dictS, dictJ, DayOfWeek.of(fOpt.head._2.toInt))
    case "hours" => UberSpeed[HourEventAction](path, dictW, dictS, dictJ, fOpt.head._2.toInt)
    case "wh" =>
      UberSpeed[WeekDayHourEventAction](
        path,
        dictW,
        dictS,
        dictJ,
        (DayOfWeek.of(fOpt("day").toInt), fOpt("hour").toInt)
      )
    case "hours_range" =>
      UberSpeed[HourRangeEventAction](path, dictW, dictS, dictJ, (fOpt("from").toInt, fOpt("to").toInt))
    case "we" => UberSpeed[AllHoursWeightedEventAction](path, dictW, dictS, dictJ, Unit)
    case "sl" => UberSpeed[BeamLengthWeightedEventAction](path, dictW, dictS, dictJ, Unit)
    case "mp" =>
      UberSpeed[MaxHourPointsEventAction](
        path,
        dictW,
        dictS,
        dictJ,
        MaxHourPointFiltered(fOpt("from").toInt, fOpt("to").toInt, fOpt("p").toInt)
      )
  }*/
}
