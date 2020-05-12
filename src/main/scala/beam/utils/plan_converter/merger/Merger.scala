package beam.utils.plan_converter.merger

trait Merger[IT, OT] {

  def transform(iter: Iterator[IT]): Iterator[OT]
}
