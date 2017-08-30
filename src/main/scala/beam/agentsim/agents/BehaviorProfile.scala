package beam.agentsim.agents

/**
  * BEAM
  */
sealed trait BehaviorProfile

final case class PersonCanUserRideHailingService(a: Int) extends BehaviorProfile
final case class SubtypeTwo(b: Option[String]) extends BehaviorProfile
