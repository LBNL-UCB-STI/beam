package beam.agentsim.agents.choice.logit

/**
  * Utility function that can be applied to evaluate an alternative based on a set of coefficients
  *
  * @param alternativeId the unique id of the alternative
  * @param params a list consisting of all utility parameters that apply for the alternative
  * @tparam A
  * @tparam T
  */
case class UtilityFunctionParams[A, T](alternativeId: A, params: List[UtilityParam[T]])

object UtilityFunctionParams {
  def empty[A, T] = new UtilityFunctionParams(None, List())
}
