package beam.side.speed.parser.operation

trait SpeedWriter[T <: Product, F[_]] {
  def write(data: T): F[Unit]
  def flush(): F[Unit]
}

object SpeedWriter {
  def apply[T <: Product, F[_]](
      implicit Writer: SpeedWriter[T, F]): SpeedWriter[T, F] = Writer
}
