package beam.side.route.model
import beam.side.route.model.WKT.Path

sealed trait WKT
case class Multiline(path: Path[Coordinate]) extends WKT

object WKT {
  type Path[_] = List[_]
}
