package beam.side.route.model

import cats.syntax.either._
import io.circe._
import io.circe.generic.semiauto._

import scala.language.higherKinds

case class Url(host: Host, path: String, query: Map[String, _ <: AnyVal])

object Url {
  implicit val urlDecoder: Decoder[Url] = deriveDecoder
}

case class Instruction(distance: Double, interval: Seq[Int], time: Long)

case class Coordinate(lon: Double, lat: Double)

object Coordinate {
  def apply(lon: Double, lat: Double): Coordinate = new Coordinate(lon, lat)

  implicit val coordinateDecoder: Decoder[Coordinate] = Decoder.decodeArray[Double].emap {
    case Array(lon, lat) => Either.catchNonFatal(new Coordinate(lon, lat)).leftMap(_ => "Invalid coordinates")
  }
}

case class Way(points: Seq[Coordinate], instructions: Seq[Instruction], wayPoints: (Coordinate, Coordinate))
