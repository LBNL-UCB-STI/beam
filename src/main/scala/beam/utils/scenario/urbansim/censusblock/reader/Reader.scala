package beam.utils.scenario.urbansim.censusblock.reader

trait Reader[T] extends AutoCloseable {
  def iterator(): Iterator[T]
}
