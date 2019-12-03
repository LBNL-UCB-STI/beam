package beam.side.route.model
import beam.side.route.model.WKT.Path

sealed trait WKT
case class Multiline(path: Path[Coordinate]) extends WKT

object Multiline {

  import Coordinate._
  import Encoder._

  implicit val multilineEncoder: Encoder[Multiline] = new Encoder[Multiline] {
    override def apply(row: Multiline): String = s"LINESTRING(${row.path.map(r => r.row).mkString(",")})"
  }
}

object WKT {
  type Path[+A] = List[A]
}
