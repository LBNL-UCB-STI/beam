package beam.side.route.model

import scala.language.higherKinds

case class Url(host: Host, path: String, query: Map[String, _ <: AnyVal])

case class Instruction(distance: Double, interval: Seq[Int], time: Long)

case class Coordinate(lon: Double, lat: Double)

case class Way(points: Seq[Coordinate], instructions: Seq[Instruction], wayPoints: (Coordinate, Coordinate))
