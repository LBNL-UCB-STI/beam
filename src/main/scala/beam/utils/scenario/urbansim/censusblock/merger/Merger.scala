package beam.utils.scenario.urbansim.censusblock.merger

trait Merger[IT, OT] {
  def merge(iter: Iterator[IT]): Iterator[OT]
}
