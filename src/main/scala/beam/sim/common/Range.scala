package beam.sim.common

import scala.util.Try
import scala.language.implicitConversions

case class DoubleComparableRange(range: Range) {

  def hasDouble(value: Double): Boolean = {
    range.lowerEndpoint <= value && value <= range.upperEndpoint
  }

  def hasOrEmpty(value: Double): Boolean = range.isEmpty || hasDouble(value)
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

  val pattern = """\[-?\d*\:-?\d*\]"""

  def apply(exp: String, closeRange: Boolean, isDouble: Boolean): Range = {
    if (!closeRange) return Range(exp, isDouble)

    if (exp == null || exp.isEmpty) Range.empty()
    else if (!exp.matches(pattern))
      throw new RuntimeException(s"Invalid range expression $exp, it should be [<NUM>:<NUM>].")
    else {
      val endpoints = exp.split(":")
      val lowerEndpoint = Try(
        endpoints(0).substring(1).toInt
      ).getOrElse(0)
      val upperEndpoint = Try(
        endpoints(1).substring(0, endpoints(1).length - 1).toInt
      ).getOrElse(Int.MaxValue)
      if (upperEndpoint < lowerEndpoint)
        throw new RuntimeException(
          s"In range expression $exp, [<lowerEndpoint>:<upperEndpoint>] upperEndpoint can't be smaller than lowerEndpoint."
        )
      Range(lowerEndpoint, upperEndpoint)
    }
  }

  def apply(exp: String, isDouble: Boolean = false): Range = {
    val softBoundValue = if (isDouble) 0 else 1
    if (exp == null || exp.isEmpty) Range.empty()
    else {
      val endpoints = exp.split(":")
      val lowerEndpoint = Try(
        endpoints(0).substring(1).toInt
        + (if (endpoints(0).startsWith("(")) 1 else 0)
      ).getOrElse(0)
      val upperEndpoint = Try(
        endpoints(1).substring(0, endpoints(1).length - 1).toInt
        - (if (endpoints(1).endsWith(")")) 1 else 0)
      ).getOrElse(Int.MaxValue)
      Range(lowerEndpoint, upperEndpoint)
    }
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

  implicit def rangeToDoubleComparableRange(range: Range): DoubleComparableRange = {
    DoubleComparableRange(range)
  }
}
