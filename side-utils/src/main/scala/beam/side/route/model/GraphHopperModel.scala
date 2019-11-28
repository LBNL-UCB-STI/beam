package beam.side.route.model

import cats.syntax.either._
import io.circe.Decoder.Result
import io.circe._
import io.circe.generic.semiauto._

import scala.language.higherKinds

case class Url(host: Host, path: String, query: Seq[(String, _ <: AnyVal)])

case class Instruction(distance: Double, interval: Seq[Int], time: Long)

object Instruction {
  implicit val instructionDecoder: Decoder[Instruction] = deriveDecoder
}

case class Coordinate(lon: Double, lat: Double)

object Coordinate {
  def apply(lon: Double, lat: Double): Coordinate = new Coordinate(lon, lat)

  implicit val coordinateDecoder: Decoder[Coordinate] = Decoder.decodeArray[Double].emap {
    case Array(lon, lat) => Either.catchNonFatal(new Coordinate(lon, lat)).leftMap(_ => "Invalid coordinates")
  }
}

case class Way(points: Seq[Coordinate], instructions: Seq[Instruction], wayPoints: (Coordinate, Coordinate))

object Way {
  implicit val wayDecoder: Decoder[Way] = new Decoder[Way] {
    override def apply(c: HCursor): Result[Way] = {
      val paths = c.downField("paths")
      for {
        coordinates  <- paths.downField("points").downField("coordinates").as[Seq[Coordinate]]
        instructions <- paths.downField("instructions").as[Seq[Instruction]]
        wayPoints    <- paths.downField("snapped_waypoints").downField("coordinates").as[Seq[Coordinate]]
      } yield new Way(coordinates, instructions, (wayPoints.head, wayPoints.last))
    }
  }
}
