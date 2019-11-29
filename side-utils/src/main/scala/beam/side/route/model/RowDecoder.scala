package beam.side.route.model

trait RowDecoder[T <: Product] {
  def apply(row: String): T
}

object RowDecoder {
  implicit class DecoderSyntax(data: String) {
    def product[T <: Product](implicit decoder: RowDecoder[T]): T = decoder.apply(data)
  }

  def apply[T <: Product](implicit decoder: RowDecoder[T]): RowDecoder[T] = decoder
}
