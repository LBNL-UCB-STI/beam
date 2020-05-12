package beam.utils.plan_converter.merger

trait Merger[IT, OT] {
  def merge(iter: Iterator[IT]): Iterator[OT]
}
