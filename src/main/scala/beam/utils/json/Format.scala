package beam.utils.json

import io.circe.Decoder.Result
import io.circe.generic.decoding.DerivedDecoder
import io.circe.generic.encoding.DerivedAsObjectEncoder
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder, HCursor, Json}
import shapeless.Lazy

class Format[T](implicit decode: Lazy[DerivedDecoder[T]], encode: Lazy[DerivedAsObjectEncoder[T]])
    extends Encoder[T]
    with Decoder[T] {
  implicit val decoder: Decoder[T] = deriveDecoder[T]
  implicit val encoder: Encoder[T] = deriveEncoder[T]

  override def apply(a: T): Json = encoder.apply(a)

  override def apply(c: HCursor): Result[T] = decoder.apply(c)
}
