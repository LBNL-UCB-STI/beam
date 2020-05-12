package beam.utils.plan_converter.reader

trait Reader[T] {

  def iterator(): Iterator[T]

}
