package beam.agentsim.scheduler

trait Trigger {
  def tick: Int
}

trait HasTriggerId {
  def triggerId: Long
}

object Trigger {

  case class TriggerWithId(trigger: Trigger, triggerId: Long) extends HasTriggerId

}
