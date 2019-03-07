package beam.agentsim.agents.choice.logit

sealed trait UtilityParamType

object UtilityParamType {

  case object Intercept extends UtilityParamType
  case object Multiplier extends UtilityParamType

  def apply(s: String): UtilityParamType = {
    s.toLowerCase match {
      case "intercept"  => Intercept
      case "multiplier" => Multiplier
      case _ =>
        throw new RuntimeException(s"Unknown Utility Parameter Type $s")
    }
  }
}
