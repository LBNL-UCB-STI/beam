package beam.side.route.model

trait Encoder[T <: Product] {
  def apply(row: T): String
}

object Encoder {
  implicit class EncoderSyntax[T <: Product](data: T) {
    def row(implicit enc: Encoder[T]): String = enc.apply(data)
  }

  def apply[T <: Product](implicit Enc: Encoder[T]): Encoder[T] = Enc
}
