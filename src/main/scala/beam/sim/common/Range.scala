package beam.sim.common

import scala.util.Try
import scala.language.implicitConversions

case class DoubleComparableRange(range: Range) {

  def hasDouble(value: Double): Boolean = {
    range.lowerBound <= value && value <= range.upperBound
  }

  def hasOrEmpty(value: Double): Boolean = range.isEmpty || hasDouble(value)
}

case class Range(lowerBound: Int, upperBound: Int) {
  val isEmpty = false

  def has(value: Int): Boolean = {
    lowerBound <= value && value <= upperBound
  }

  def hasOrEmpty(value: Int): Boolean = {
    isEmpty || has(value)
  }

  override def toString: String = s"{${lowerBound}:${upperBound}}"
}

object Range {

  def apply(pattern: String, isDouble: Boolean = false): Range = {
    val softBoundValue = if (isDouble) 0 else 1
    if (pattern == null || pattern.isEmpty) Range.empty()
    else {
      val bounds = pattern.split(":")
      val lowerBound = Try(
        bounds(0).substring(1).toInt
        + (if (bounds(0).startsWith("(")) softBoundValue else 0)
      ).getOrElse(0)
      val upperBound = Try(
        bounds(1).substring(0, bounds(1).length - 1).toInt
        - (if (bounds(1).endsWith(")")) softBoundValue else 0)
      ).getOrElse(Int.MaxValue)
      Range(lowerBound, upperBound)
    }
  }

  def apply(lowerBound: Int, upperBound: Int): Range = {
    if (lowerBound == 0 && upperBound == 0)
      Range.empty()
    else
      new Range(lowerBound, upperBound)
  }

  def empty(): Range = new Range(0, 0) {
    override val isEmpty = true
  }

  implicit def rangeToDoubleComparableRange(range: Range): DoubleComparableRange = {
    DoubleComparableRange(range)
  }
}
