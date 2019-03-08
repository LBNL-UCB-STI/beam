package beam.agentsim.agents.choice.logit

sealed trait UtilityFunctionParamType {
  def op(coefficient: Double, paramValue: Double): Double
}

/**
  * The type of an [[UtilityFunctionParam]]. For now only intercepts and multiplier are supported.
  */
object UtilityFunctionParamType {

  case object Intercept extends UtilityFunctionParamType {
    override def op(coefficient: Double, paramValue: Double): Double = coefficient
  }
  case object Multiplier extends UtilityFunctionParamType {
    override def op(coefficient: Double,paramValue: Double): Double = coefficient * paramValue
  }

  def apply(s: String): UtilityFunctionParamType = {
    s.toLowerCase match {
      case "intercept"  => Intercept
      case "multiplier" => Multiplier
      case _ =>
        throw new RuntimeException(s"Unknown Utility Parameter Type $s")
    }
  }
}
