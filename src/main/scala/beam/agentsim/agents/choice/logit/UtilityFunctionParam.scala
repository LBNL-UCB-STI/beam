package beam.agentsim.agents.choice.logit

/**
  * One utility parameter of a [[UtilityFunction]] to evaluate the utility of an [[Alternative]]
  *
  * @param paramId the unique parameter (e.g. costs)
  * @param paramType the type of the parameter as [[UtilityFunctionParamType]] (e.g. [[UtilityFunctionParamType.Intercept]])
  * @param paramValue the coefficient value
  * @tparam T
  */
case class UtilityFunctionParam[T](val paramId: T, val paramType: UtilityFunctionParamType, val paramValue: Double)