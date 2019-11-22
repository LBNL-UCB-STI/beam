package beam.side.route.model

import scala.language.higherKinds

case class Url(host: Host, path: String, query: Map[String, _ <: AnyVal])
case class Instruction(way: List[Coordinate])
case class Coordinate(name: String, lon: Double, lat: Double)

sealed trait WKT

trait GHRequest[F[_]] {
  def request(url: Url): F[String]
}

trait RequestMatrializer[F[_]] extends (String => F[Instruction])
trait WKTConverter[F[_]] extends (Instruction => F[WKT])
