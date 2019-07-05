package beam.side.speed.parser

import java.nio.file.Paths

import beam.side.speed.model.{UberDirectedWay, UberSpeedEvent, UberWaySpeed, WayMetric}
import beam.side.speed.parser.data.{DataLoader, JunctionDictionary, UnarchivedSource}

import scala.collection.parallel.immutable.ParMap

class UberNodeSpeed(path: String, dict: JunctionDictionary) extends DataLoader[UberSpeedEvent] with UnarchivedSource {
  import beam.side.speed.model.UberSpeedEvent._

  private val speeds: ParMap[(String, String), Seq[WayMetric]] = load(Paths.get(path))
    .foldLeft(Map[(String, String), Seq[WayMetric]]())(
      (acc, s) =>
        acc + ((s.startJunctionId, s.endJunctionId) -> (acc
          .getOrElse((s.startJunctionId, s.endJunctionId), Seq()) :+ WayMetric(
          s.dateTime,
          s.speedMphMean,
          s.speedMphStddev
        )))
    )
    .par
    .map{ case ((s, e), v) => UberDirectedWay(dict(s), dict(e), v)}

}
