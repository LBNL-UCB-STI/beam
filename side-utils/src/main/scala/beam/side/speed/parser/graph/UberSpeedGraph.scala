package beam.side.speed.parser.graph
import java.nio.file.Paths

import beam.side.speed.model._
import beam.side.speed.parser.data.{DataLoader, Dictionary, UnarchivedSource}
import beam.side.speed.parser.operation.SpeedDataExtractor
import scalax.collection.edge.Implicits._
import scalax.collection.edge.LBase.LEdgeImplicits
import scalax.collection.edge.LkDiEdge
import scalax.collection.immutable.Graph

import scala.collection.mutable

class UberSpeedGraph(
  path: Seq[String],
  dictW: Dictionary[UberOsmWays, Long, String],
  dictS: Dictionary[UberOsmWays, String, Long],
  dictJ: Dictionary[UberOsmNode, String, Long]
) extends SpeedDataExtractor[Option]
    with DataLoader[UberSpeedEvent]
    with UnarchivedSource {

  object WayMetricsLabel extends LEdgeImplicits[WayMetrics]
  import WayMetricsLabel._

  private val (ways, nodes) = {
    val (w, n) = load(path.map(Paths.get(_)))
      .foldLeft((mutable.Map[String, Seq[WayMetric]](), mutable.Map[UberWay, Seq[WayMetric]]())) {
        case ((accW, accN), s) =>
          val w = WayMetric(s.dateTime, s.speedMphMean, s.speedMphStddev)
          val uw = UberWay(s.segmentId, s.startJunctionId, s.endJunctionId)
          (
            accW += (s.segmentId -> (accW.getOrElse(s.segmentId, Seq()) :+ w)),
            accN += (uw          -> (accN.getOrElse(uw, Seq()) :+ w))
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

  private def shorterPath(origNodeId: Long, destNodeId: Long): Option[nodeGraph.Path] = {
    def shorter(p1: Option[nodeGraph.Path], p2: Option[nodeGraph.Path]) =
      if (p1.map(_.length).getOrElse(Integer.MAX_VALUE) == p2.map(_.length).getOrElse(Integer.MAX_VALUE)) {
        val mp1 = p1.map(_.edges.flatMap(_.metrics)).getOrElse(Seq())
        val mp2 = p2.map(_.edges.flatMap(_.metrics)).getOrElse(Seq())
        if (mp1.size == mp2.size) {
          if (mp1.map(_.speedMphMean).sum / mp1.size >= mp2.map(_.speedMphMean).sum / mp2.size) p1 else p2
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

  override def speed(origNodeId: Long, destNodeId: Long): Option[Seq[WayMetrics]] =
    for {
      path <- shorterPath(origNodeId, destNodeId) if path.length < 15
      metrics = path.edges.map(_.label.asInstanceOf[WayMetrics]).toSeq
    } yield metrics

  override def speed(
    osmId: UberSpeedGraph.this.WayId
  ): Option[Seq[WayMetric]] =
    for {
      wayId <- dictW(osmId)
      wm    <- ways.get(wayId)
    } yield wm
}

object UberSpeedGraph {

  def apply(
    path: Seq[String],
    dictW: Dictionary[UberOsmWays, Long, String],
    dictS: Dictionary[UberOsmWays, String, Long],
    dictJ: Dictionary[UberOsmNode, String, Long]
  ): UberSpeedGraph = new UberSpeedGraph(path, dictW, dictS, dictJ)
}
