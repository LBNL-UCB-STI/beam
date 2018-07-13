package beam.agentsim.scheduler

trait Trigger {
  def tick: Double
}

case class TriggerWithId(trigger: Trigger, triggerId: Long)
