package beam.utils.json

import beam.router.BeamRouter.Location
import io.circe.Decoder.Result
import io.circe.{Decoder, Encoder, HCursor, Json}

object LocationFormat extends Encoder[Location] with Decoder[Location] {

  override def apply(a: Location): Json = Json.obj(
    ("x", Json.fromString(a.getX.toString)),
    ("y", Json.fromString(a.getY.toString))
  )

  override def apply(c: HCursor): Result[Location] = {
    for {
      x <- c.downField("x").as[String]
      y <- c.downField("y").as[String]
    } yield {
      new Location(x.toDouble, y.toDouble)
    }
  }
}
