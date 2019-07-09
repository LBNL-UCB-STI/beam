package beam.side.speed.parser

import java.nio.file.Paths
import java.time.DayOfWeek

import beam.side.speed.model.FilterEvent.AllHoursDaysEventAction.AllHoursDaysEventAction
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
    nodeGraph
      .find(origNodeId)
      .flatMap(o => nodeGraph.find(destNodeId).flatMap(d => o.shortestPathTo(d)))
      .map(p => p.edges.foldLeft(Seq[WayMetric]())((acc, e2) => acc ++ e2.metrics))
      .map(dropToWeek)
      .map(_.waySpeed[T](fOpt))

  def wayParts(origNodeId: Long, destNodeId: Long): Option[String] =
    nodeGraph
      .find(origNodeId)
      .flatMap(o => nodeGraph.find(destNodeId).flatMap(d => o.shortestPathTo(d)))
      .map(p => p.edges.foldLeft(Seq[Float]())((acc, e2) => acc :+ e2.metrics.map(_.speedMphMean).max))
      .map(_.mkString(","))

  private def dropToWeek(metrics: Seq[WayMetric]): UberWaySpeed = {
    val week = metrics
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
  }
}
