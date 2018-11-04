package beam.agentsim.agents.choice.mode
import scala.util.Try

case class Range(lowerBound: Int, upperBound: Int) {
  val isEmpty = false

  def has(value: Int): Boolean = {
    lowerBound <= value && value <= upperBound
  }

  def hasOrEmpty(value: Int): Boolean = {
    isEmpty || has(value)
  }
}

object Range {

  def apply(pattern: String): Range = {
    if (pattern == null || pattern.isEmpty) return Range.empty()
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

  def apply(lowerBound: Int, upperBound: Int): Range = {
    if (lowerBound == 0 && upperBound == 0)
      Range.empty()
    else
      new Range(lowerBound, upperBound)
  }

  def empty(): Range = new Range(0, 0) {
    override val isEmpty = true
  }
}
