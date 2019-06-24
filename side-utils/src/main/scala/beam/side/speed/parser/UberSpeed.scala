package beam.side.speed.parser

import java.nio.file.Paths
import java.time.DayOfWeek

import beam.side.speed.model.FilterEvent.WeekDayEventAction.WeekDayEventAction
import beam.side.speed.model._

class UberSpeed[T <: FilterEventAction](path: String, dict: UberOsmDictionary, fOpt: T#Filtered)
    extends DataLoader[UberSpeedEvent]
    with UnarchivedSource {
  import beam.side.speed.model.UberSpeedEvent._
  import WayFilter._

  lazy val speeds = load(Paths.get(path))
    .foldLeft(Map[String, Seq[UberSpeedEvent]]())(
      (acc, s) => acc + (s.segmentId -> (acc.getOrElse(s.segmentId, Seq()) :+ s))
    )
  /*.map {
      case (segmentId, grouped) => segmentId -> dropToWeek(segmentId, grouped)
    }*/

  private lazy val filterSpeeds: Map[String, WaySpeed] = Map()
  /*  speeds.values
      .map(_.waySpeed[WeekDayEventAction](DayOfWeek.WEDNESDAY))*/

  def speed(osmId: Long): Option[WaySpeed] = dict(osmId).flatMap(s => filterSpeeds.get(s))

  private def dropToWeek(segmentId: String, junctions: Seq[UberSpeedEvent]): UberWaySpeed = {
    println(s"Way $segmentId")
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

object UberSpeed {

  def apply[T <: FilterEventAction](path: String, dict: UberOsmDictionary, fOpt: T#Filtered): UberSpeed[T] =
    new UberSpeed(path, dict, fOpt)
}
