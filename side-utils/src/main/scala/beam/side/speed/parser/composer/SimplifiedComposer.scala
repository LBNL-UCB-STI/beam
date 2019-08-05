package beam.side.speed.parser.composer
import java.time.DayOfWeek

import beam.side.speed.model._
import beam.side.speed.parser.data.Dictionary
import beam.side.speed.parser.operation.ObservationComposer

class SimplifiedComposer(
  dictSegments: Dictionary[UberOsmWays, String, Long],
  beams: Dictionary[BeamSpeed, Long, BeamSpeed]
) extends ObservationComposer[Option, Seq[WayMetrics], UberWaySpeed] {

  override def compose(input: Seq[WayMetrics]): Option[UberWaySpeed] =
    input
      .map(e => dictSegments(e.sId).flatMap(beams(_)).map(b => b.speed -> b.length))
      .collect {
        case Some(t) => t
      } match {
      case Nil => None
      case xs  => Some(maxWeek(xs))
    }

  private def maxWeek(metrics: Seq[(Float, Double)]): UberWaySpeed =
    UberWaySpeed(
      Seq(
        UberDaySpeed(
          DayOfWeek.TUESDAY,
          metrics.map { case (f, s) => UberHourSpeed(10, f, f, s.toFloat, f) }.toList
        )
      )
    )
}
