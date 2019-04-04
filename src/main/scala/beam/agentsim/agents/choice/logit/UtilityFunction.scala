package beam.agentsim.agents.choice.logit

/**
  * Utility function that can be applied to evaluate an alternative based on a set of coefficients.
  *
  * Each [[alternativeTypeId]] *should* be unique.
  * In some logit model implementations (e.g. [[MultinomialLogit]]) the provided set of [[UtilityFunctionParam]]
  * is aggregated if multiple [[UtilityFunction]]s with the same [[alternativeTypeId]] are provided and
  * the [[params]] sets are joined (and duplicates dropped, if any!)
  *
  * @param alternativeTypeId the unique id of the alternative
  * @param params a list consisting of all utility parameters that apply for the alternative
  * @tparam A
  * @tparam T
  */
case class UtilityFunction[T](alternativeTypeId: AlternativeType, params: Set[UtilityFunctionParam[T]])
