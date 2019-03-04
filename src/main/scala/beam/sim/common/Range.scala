package beam.sim.common

import scala.util.Try
import scala.language.implicitConversions

case class DoubleComparableRange(range: Range) {

  def hasDouble(value: Double) = {
    range.lowerBound <= value && value <= range.upperBound
  }

  def hasOrEmpty(value: Double) = range.isEmpty || hasDouble(value)
}

case class Range(lowerEndpoint: Int, upperEndpoint: Int) {
  val isEmpty = false

  def has(value: Int): Boolean = {
    lowerEndpoint <= value && value <= upperEndpoint
  }

  def hasOrEmpty(value: Int): Boolean = {
    isEmpty || has(value)
  }
}

object Range {

  def apply(pattern: String): Range =
    if (pattern == null || pattern.isEmpty) Range.empty()
    else {
      val bounds = pattern.split(":")
      val lowerBound = Try(
        bounds(0).substring(1).toInt
        + (if (bounds(0).startsWith("(")) 1 else 0)
      ).getOrElse(0)
      val upperBound = Try(
        bounds(1).substring(0, bounds(1).length - 1).toInt
        - (if (bounds(1).endsWith(")")) 1 else 0)
      ).getOrElse(Int.MaxValue)
      Range(lowerBound, upperBound)
    }

  def apply(lowerEndpoint: Int, upperEndpoint: Int): Range = {
    if (lowerEndpoint == 0 && upperEndpoint == 0)
      Range.empty()
    else
      new Range(lowerEndpoint, upperEndpoint)
  }

  def empty(): Range = new Range(0, 0) {
    override val isEmpty = true
  }

  implicit def rangeToDoubleComparableRange(range: Range) = {
    new DoubleComparableRange(range)
  }
}
