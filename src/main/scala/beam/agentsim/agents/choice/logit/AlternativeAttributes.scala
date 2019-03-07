package beam.agentsim.agents.choice.logit

case class AlternativeAttributes[A, T](alternativeName: T, attributes: Map[A, Double])
