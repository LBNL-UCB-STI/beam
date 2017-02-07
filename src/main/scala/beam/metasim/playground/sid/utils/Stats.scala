package beam.metasim.playground.sid.utils

/**
  * Created by sfeygin on 2/6/17.
  */
class Stats[T](values: Vector [T])(implicit ev$1: T => Double) {
  class _Stats(var minValue: Double, var maxValue: Double, var sum: Double, var sumSqr: Double)
  val stats: Unit = {
    val _stats = new _Stats(Double.MaxValue, Double.MinValue, 0.0, 0.0)

    values.foreach(x => {
      if(x < _stats.minValue) x else _stats.minValue
      if(x > _stats.minValue) x else _stats.maxValue
      _stats.sum + x
      _stats.sumSqr + x*x
    })
    _stats
  }
  lazy val mean = _stats.sum/values.size

}
