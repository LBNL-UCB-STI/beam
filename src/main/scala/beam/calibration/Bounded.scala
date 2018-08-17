package beam.calibration

import com.sigopt.model.Bounds

trait Bounded[T] {
  def bound(minValue: T, maxValue: T): Bounds
}

object Bounded {

  implicit object DoubleBounded extends Bounded[Double] {
    override def bound(minValue: Double, maxValue: Double): Bounds = new Bounds(minValue, maxValue)
  }

  implicit object IntBounded extends Bounded[Int] {
    override def bound(minValue: Int, maxValue: Int): Bounds =
      new Bounds(minValue.toDouble, maxValue.toDouble)
  }

  def getBounds[T: Bounded](minValue: T, maxValue: T): Bounds = {
    implicitly[Bounded[T]].bound(minValue, maxValue)
  }

}
