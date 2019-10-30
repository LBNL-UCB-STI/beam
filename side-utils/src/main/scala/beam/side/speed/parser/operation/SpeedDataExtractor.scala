package beam.side.speed.parser.operation
import beam.side.speed.model.{WayMetric, WayMetrics}

trait SpeedDataExtractor[M[_]] {

  type NodeId = Long
  type WayId = Long

  def speed(origNodeId: NodeId, destNodeId: NodeId): M[Seq[WayMetrics]]
  def speed(osmId: WayId): M[Seq[WayMetric]]
}

object SpeedDataExtractor {
  def apply[F[_]](
      implicit Extractor: SpeedDataExtractor[F]): SpeedDataExtractor[F] =
    Extractor
}
