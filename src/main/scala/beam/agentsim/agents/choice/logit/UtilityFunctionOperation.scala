package beam.agentsim.agents.choice.logit

sealed trait UtilityFunctionOperation {
  def apply(value: Double): Double
  def toMap: Map[String, Double]
}

/**
  * Operation one can execute on a utility function.
  */

object UtilityFunctionOperation {

  case class Intercept(coefficient: Double) extends UtilityFunctionOperation {
    override def apply(value: Double): Double = coefficient
    override def toMap: Map[String, Double] = Map("intercept" -> coefficient)
  }

  case class Multiplier(coefficient: Double) extends UtilityFunctionOperation {
    override def apply(value: Double): Double = coefficient * value
    override def toMap: Map[String, Double] = Map("multiplier" -> coefficient)
  }

  def apply(s: String, value: Double): UtilityFunctionOperation = {
    (s.toLowerCase, value) match {
      case ("intercept", _)  => Intercept(value)
      case ("asc", _)        => Intercept(value)
      case ("multiplier", _) => Multiplier(value)
      case _                 => throw new RuntimeException(s"Unknown Utility Parameter Type $s")
    }
  }
  def toMap: Map[String, Double] = {
    Map.empty[String, Double]
  }
}
