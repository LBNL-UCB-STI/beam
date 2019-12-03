package beam.side.route.processing
import beam.side.route.model.{Instruction, WKT}

import scala.language.higherKinds

trait WKTConverter[F[_]] extends (Instruction => F[_ <: WKT])

object WKTConverter {
  def apply[F[_]](implicit converter: WKTConverter[F]): WKTConverter[F] = converter
}
