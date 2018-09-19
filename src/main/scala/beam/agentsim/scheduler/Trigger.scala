package beam.agentsim.scheduler

trait Trigger {
  def tick: Int
}

object Trigger {

  case class TriggerWithId(trigger: Trigger, triggerId: Long)

}
