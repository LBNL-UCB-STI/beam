package beam.utils.plan_converter.reader

trait Reader[T] extends AutoCloseable {
  def iterator(): Iterator[T]
}
