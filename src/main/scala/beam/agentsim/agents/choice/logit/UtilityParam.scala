package beam.agentsim.agents.choice.logit

/**
  * One utility parameter used by the [[AlternativeParams]] to evaluate the utility of an alternative
  *
  * @param param the parameter (e.g. costs)
  * @param paramType the type of the parameter as [[UtilityParamType]] (e.g. [[UtilityParamType.Intercept]])
  * @param paramValue the coefficient value
  * @tparam T
  */
case class UtilityParam[T](val param: T, val paramType: UtilityParamType, val paramValue: Double)
