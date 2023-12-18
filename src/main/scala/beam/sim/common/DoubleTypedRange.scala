package beam.sim.common

import scala.util.Try

/**
  * @author Dmitry Openkov
  */
case class DoubleTypedRange(lowerBound: Double, upperBound: Double) {
  def isEmpty: Boolean = lowerBound > upperBound

  def has(value: Double): Boolean = {
    lowerBound <= value && value <= upperBound
  }

  def hasOrEmpty(value: Double): Boolean = {
    has(value) || isEmpty
  }

  override def toString: String = s"[$lowerBound:$upperBound]"
}

object DoubleTypedRange {

  def apply(pattern: String): DoubleTypedRange = {
    if (pattern == null || pattern.isEmpty || !pattern.contains(":")) empty()
    else {
      val bounds = pattern.split(":")
      val left = Try(
        bounds(0).substring(1).toDouble
      ).getOrElse(0.0)
      val right = Try(
        bounds(1).substring(0, bounds(1).length - 1).toDouble
      ).getOrElse(Double.MaxValue)
      val lowerBound = if (bounds(0).startsWith("(")) Math.nextAfter(left, left + 1) else left
      val upperBound = if (bounds(1).endsWith(")")) Math.nextAfter(right, right - 1) else right
      DoubleTypedRange(lowerBound, upperBound)
    }
  }

  def empty(): DoubleTypedRange = DoubleTypedRange(Double.MinPositiveValue, 0)
}
