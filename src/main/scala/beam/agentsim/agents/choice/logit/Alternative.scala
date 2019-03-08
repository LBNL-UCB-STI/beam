package beam.agentsim.agents.choice.logit

/**
  * Represents one alternative available to an agent to choose from and is used to evaluate one or a set of several
  * alternatives an agent can choose from in [[MultinomialLogit]]
  *
  * @param alternativeId the general identifier of the alternative that should be evaluated (e.g. car)
  * @param attributes the attributes of the alternative that should be taken into account for evaluation
  * @tparam A
  * @tparam T
  */
case class Alternative[A, T](alternativeId: T, attributes: Map[A, Double])