package beam.side.speed.parser

import java.nio.file.Paths
import java.time.DayOfWeek

import beam.side.speed.model.FilterEvent.AllHoursDaysEventAction.AllHoursDaysEventAction
import beam.side.speed.model.FilterEvent.AllHoursWeightedEventAction.AllHoursWeightedEventAction
import beam.side.speed.model.FilterEvent.HourEventAction.HourEventAction
import beam.side.speed.model.FilterEvent.HourRangeEventAction.HourRangeEventAction
import beam.side.speed.model.FilterEvent.WeekDayEventAction.WeekDayEventAction
import beam.side.speed.model.FilterEvent.WeekDayHourEventAction.WeekDayHourEventAction
import beam.side.speed.model._
import beam.side.speed.parser.data.{DataLoader, JunctionDictionary, UberOsmDictionary, UnarchivedSource}
import scalax.collection.edge.Implicits._
import scalax.collection.edge.LBase.LEdgeImplicits
import scalax.collection.edge.LkDiEdge
import scalax.collection.immutable.Graph

import scala.reflect.ClassTag

class UberSpeed[T <: FilterEventAction](
  path: String,
  dictW: UberOsmDictionary,
  dictJ: JunctionDictionary,
  fOpt: T#Filtered
)(
  implicit t: ClassTag[T],
  wf: WayFilter[T#FilterEvent, T#Filtered]
) extends DataLoader[UberSpeedEvent]
    with UnarchivedSource {

  object WayMetricsLabel extends LEdgeImplicits[WayMetrics]
  import WayMetricsLabel._
  import beam.side.speed.model.UberSpeedEvent._

  private val (ways, nodes) = {
    val (w, n) = load(Paths.get(path))
      .foldLeft((Map[String, Seq[WayMetric]](), Map[UberWay, Seq[WayMetric]]())) {
        case ((accW, accN), s) =>
          val w = WayMetric(s.dateTime, s.speedMphMean, s.speedMphStddev)
          val uw = UberWay(s.segmentId, s.startJunctionId, s.endJunctionId)
          (
            accW + (s.segmentId -> (accW.getOrElse(s.segmentId, Seq()) :+ w)),
            accN + (uw          -> (accN.getOrElse(uw, Seq()) :+ w))
          )
      }
    (
      w.par,
      n.par
        .map { case (uw, v) => (dictJ(uw.startJunctionId), dictJ(uw.endJunctionId), uw.segmentId, v) }
        .collect { case (Some(s), Some(e), sId, ws) => UberDirectedWay(s, e, sId, ws) }
    )
  }

  private val nodeGraph: Graph[Long, LkDiEdge] = Graph(
    nodes.map { case UberDirectedWay(s, e, sId, w) => (s ~+#> e)(WayMetrics(sId, w)) }.seq.toSeq: _*
  )

  def speed(osmId: Long): Option[WaySpeed] =
    dictW(osmId).flatMap(s => ways.get(s).map(dropToWeek).map(_.waySpeed[T](fOpt)))

  def way(origNodeId: Long, destNodeId: Long): Option[WaySpeed] =
    shorterPath(origNodeId, destNodeId)
      .filter(_.length < 15)
      .map(_.edges.map(_.metrics.map(_.speedMphMean).max).toSeq)
      .map(maxWeek)
      .map(_.waySpeed[T](fOpt))

  def wayPartsMax(origNodeId: Long, destNodeId: Long): Option[String] = {
    shorterPath(origNodeId, destNodeId)
      .map(p => p.edges.foldLeft(Seq[Float]())((acc, e2) => acc :+ e2.metrics.map(_.speedMphMean).max))
      .map(_.mkString(","))
  }

  private def shorterPath(origNodeId: Long, destNodeId: Long) = {
    def shorter(p1: Option[nodeGraph.Path], p2: Option[nodeGraph.Path]) =
      if (p1.map(_.length).getOrElse(Integer.MAX_VALUE) == p2.map(_.length).getOrElse(Integer.MAX_VALUE)) {
        val mp1 = p1.map(_.edges.flatMap(_.metrics)).getOrElse(Seq())
        val mp2 = p2.map(_.edges.flatMap(_.metrics)).getOrElse(Seq())
        if (mp1.size == mp2.size) {
          if ( mp1.map(_.speedMphMean).sum / mp1.size >= mp2.map(_.speedMphMean).sum / mp2.size) p1 else p2
        } else if (mp1.size > mp2.size) p1
        else p2
      } else if (p1.map(_.length).getOrElse(Integer.MAX_VALUE) < p2.map(_.length).getOrElse(Integer.MAX_VALUE)) p1
      else p2

    val path1 = nodeGraph
      .find(origNodeId)
      .flatMap(o => nodeGraph.find(destNodeId).flatMap(d => o.shortestPathTo(d)))
    val path2 = nodeGraph
      .find(destNodeId)
      .flatMap(d => nodeGraph.find(origNodeId).flatMap(o => d.shortestPathTo(o)))

    shorter(path1, path2)
  }

  def wayParts(origNodeId: Long, destNodeId: Long): Option[Seq[UberDaySpeed]] = {
    shorterPath(origNodeId, destNodeId)
      .filter(_.length < 15)
      .map(_.edges.foldLeft(Seq[WayMetric]())((acc, e2) => acc ++ e2.metrics))
      .map { s =>
        val week = s
          .groupBy(e => (e.dateTime.getHour, e.dateTime.getDayOfWeek))
          .toSeq
          .map {
            case ((h, dw), g) =>
              val speedMax = g.map(_.speedMphMean).max * 1.60934 / 3.6
              val speedAvg = g.map(_.speedMphMean).sum * 1.60934 / (3.6 * g.size)
              val devMax = g.map(_.speedMphStddev).max * 1.60934 / 3.6
              val speedMedian = Median.findMedian(g.map(_.speedMphMean).toArray) * 1.60934 / 3.6
              (dw, UberHourSpeed(h, speedMedian.toFloat, speedAvg.toFloat, devMax.toFloat, speedMax.toFloat))
          }
          .groupBy(_._1)
          .mapValues(s => s.map(_._2))
          .map {
            case (d, uhs) => UberDaySpeed(d, uhs.toList)
          }
        week.toSeq
      }
  }

  private def maxWeek(metrics: Seq[Float]): UberWaySpeed =
    UberWaySpeed(Seq(UberDaySpeed(DayOfWeek.TUESDAY, metrics.map(f => (f * 1.60934 / 3.6).toFloat).map(f => UberHourSpeed(10, f, f, f, f)).toList)))


  private def dropToWeek(metrics: Seq[WayMetric]): UberWaySpeed = {
    val week = metrics
      .groupBy(e => (e.dateTime.getHour, e.dateTime.getDayOfWeek))
      .toSeq
      .map {
        case ((h, dw), g) =>
          val speedMax = g.map(_.speedMphMean).max * 1.60934 / 3.6
          val speedAvg = g.map(_.speedMphMean).sum * 1.60934 / (3.6 * g.size)
          val devMax = g.map(_.speedMphStddev).max * 1.60934 / 3.6
          val speedMedian = Median.findMedian(g.map(_.speedMphMean).toArray) * 1.60934 / 3.6
          (dw, UberHourSpeed(h, speedMedian.toFloat, speedAvg.toFloat, devMax.toFloat, speedMax.toFloat))
      }
      .groupBy(_._1)
      .mapValues(s => s.map(_._2))
      .map {
        case (d, uhs) => UberDaySpeed(d, uhs.toList)
      }
    UberWaySpeed(week.toSeq)
  }
}

object UberSpeed {

  def apply[T <: FilterEventAction](
    path: String,
    dictW: UberOsmDictionary,
    dictJ: JunctionDictionary,
    fOpt: T#Filtered
  )(
    implicit t: ClassTag[T],
    wf: WayFilter[T#FilterEvent, T#Filtered]
  ): UberSpeed[T] =
    new UberSpeed(path, dictW, dictJ, fOpt)

  def apply(
    mode: String,
    fOpt: Map[String, String],
    path: String,
    dictW: UberOsmDictionary,
    dictJ: JunctionDictionary
  ): UberSpeed[_] = mode match {
    case "all"   => UberSpeed[AllHoursDaysEventAction](path, dictW, dictJ, Unit)
    case "wd"    => UberSpeed[WeekDayEventAction](path, dictW, dictJ, DayOfWeek.of(fOpt.head._2.toInt))
    case "hours" => UberSpeed[HourEventAction](path, dictW, dictJ, fOpt.head._2.toInt)
    case "wh" =>
      UberSpeed[WeekDayHourEventAction](path, dictW, dictJ, (DayOfWeek.of(fOpt("day").toInt), fOpt("hour").toInt))
    case "hours_range" => UberSpeed[HourRangeEventAction](path, dictW, dictJ, (fOpt("from").toInt, fOpt("to").toInt))
    case "we" => UberSpeed[AllHoursWeightedEventAction](path, dictW, dictJ, Unit)
  }
}
