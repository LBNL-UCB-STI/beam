package beam.side.speed.parser

import java.nio.file.Paths

import beam.side.speed.model._

import scala.collection.parallel
import scala.collection.parallel.immutable.ParMap
import scala.reflect.ClassTag

class UberSpeed[T <: FilterEventAction](path: String, dict: UberOsmDictionary, fOpt: T#Filtered)(
  implicit t: ClassTag[T],
  wf: WayFilter[T#FilterEvent, T#Filtered]
) extends DataLoader[UberSpeedEvent]
    with UnarchivedSource {
  import beam.side.speed.model.UberSpeedEvent._

  private val speeds: ParMap[String, UberWaySpeed] = load(Paths.get(path))
    .foldLeft(Map[String, Seq[UberSpeedEvent]]())(
      (acc, s) => acc + (s.segmentId -> (acc.getOrElse(s.segmentId, Seq()) :+ s))
    )
    .par
    .map {
      case (segmentId, grouped) => segmentId -> dropToWeek(segmentId, grouped)
    }

  private val filterSpeeds: parallel.ParMap[String, WaySpeed] =
    speeds.mapValues(_.waySpeed[T](fOpt))

  def speed(osmId: Long): Option[WaySpeed] = dict(osmId).flatMap(s => filterSpeeds.get(s))

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

object UberSpeed {

  def apply[T <: FilterEventAction](path: String, dict: UberOsmDictionary, fOpt: T#Filtered)(
    implicit t: ClassTag[T],
    wf: WayFilter[T#FilterEvent, T#Filtered]
  ): UberSpeed[T] =
    new UberSpeed(path, dict, fOpt)
}
