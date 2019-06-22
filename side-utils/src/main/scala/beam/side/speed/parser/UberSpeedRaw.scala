package beam.side.speed.parser

import java.nio.file.Paths
import java.time.DayOfWeek

import beam.side.speed.model.FilterEvent.WeekDayEventAction.WeekDayEventAction
import beam.side.speed.model.{UberDaySpeed, UberHourSpeed, UberSpeedEvent, UberWaySpeed}

import scala.collection.immutable

class UberSpeedRaw(path: String) extends DataLoader[UberSpeedEvent] with UnarchivedSource {
  import beam.side.speed.model.UberSpeedEvent._
  import WayFilter._

  private lazy val speeds: immutable.Iterable[UberWaySpeed] = load(Paths.get(path))
    .foldLeft(Map[String, Seq[UberSpeedEvent]]())(
      (acc, s) => acc + (s.segmentId -> (acc.getOrElse(s.segmentId, Seq()) :+ s))
    )
    .map {
      case (segmentId, grouped) => dropToWeek(segmentId, grouped)
    }

  def filterSpeeds =
    speeds
      .map(_.waySpeed[WeekDayEventAction](DayOfWeek.WEDNESDAY))

  private def dropToWeek(segmentId: String, junctions: Seq[UberSpeedEvent]): UberWaySpeed = {
    val week = junctions
      .groupBy(e => (e.dateTime.getHour, e.dateTime.getDayOfWeek))
      .map {
        case ((h, dw), g) =>
          val speedAvg = g.map(_.speedMphMean).sum / g.size
          val devMax = g.map(_.speedMphStddev).max
          val speedMedian = Median.findMedian(g.map(_.speedMphMean).toArray)
          (dw, UberHourSpeed(h, speedMedian, speedAvg, devMax))
      }
      .groupBy(_._1)
      .mapValues(_.values)
      .map {
        case (d, uhs) => UberDaySpeed(d, uhs.toSeq)
      }
    UberWaySpeed(segmentId, week.toSeq)
  }
}

object UberSpeedRaw {
  def apply(path: String): UberSpeedRaw = new UberSpeedRaw(path)
}
