package beam.side.route.model

import cats.syntax.either._
import io.circe.Decoder.Result
import io.circe._
import io.circe.generic.semiauto._

import scala.language.higherKinds

case class Url(host: Host, path: String, query: Seq[(String, _)])

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

  implicit val coordinateEncoder: Encoder[Coordinate] = new Encoder[Coordinate] {
    override def apply(row: Coordinate): String = s"${row.lon} ${row.lat}"
  }
}

case class Way(
  distance: Double,
  elevation: Double,
  points: Seq[Coordinate],
  instructions: Seq[Instruction],
  wayPoints: (Coordinate, Coordinate)
)

object Way {

  implicit val wayDecoder: Decoder[Way] = new Decoder[Way] {
    override def apply(c: HCursor): Result[Way] = {
      for {
        coordinates  <- c.downField("points").downField("coordinates").as[Seq[Coordinate]]
        instructions <- c.downField("instructions").as[Seq[Instruction]]
        wayPoints    <- c.downField("snapped_waypoints").downField("coordinates").as[Seq[Coordinate]]
      } yield new Way(coordinates, instructions, (wayPoints.head, wayPoints.last))
    }
  }
}

case class GHPaths(ways: Seq[Way])

object GHPaths {
  import Way._

  implicit val pathsDecoder: Decoder[GHPaths] = new Decoder[GHPaths] {
    override def apply(c: HCursor): Result[GHPaths] = c.downField("paths").as[Seq[Way]].map(GHPaths(_))
  }
}
