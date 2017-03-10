package beam.agentsim.agents


trait Trigger {
  def tick: Double
}

case class TriggerWithId(val trigger: Trigger, val triggerId: Long)

