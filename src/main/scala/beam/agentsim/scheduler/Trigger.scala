package beam.agentsim.scheduler

trait Trigger {
  def tick: Double
}

object Trigger {

  case class TriggerWithId(trigger: Trigger, triggerId: Long)

}
