package beam.agentsim.agents


trait Trigger {
  def tick: Double
}

case class TriggerWithId(trigger: Trigger, triggerId: Long)

