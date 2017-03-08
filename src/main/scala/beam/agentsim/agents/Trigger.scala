package beam.agentsim.agents

import akka.actor.ActorRef

import scala.math.Ordered.orderingToOrdered

case class TriggerData(val agent: ActorRef, val tick: Double, val priority: Int = 0, val id: Int = 0) {
  require(tick>=0, "Negative timestamps not supported!")
}

abstract class Trigger[T]() {
  val triggerData: TriggerData
  def copy(newId: Int):Trigger[T]
  def compare(that: Trigger[T]): Int = (that.triggerData.tick, that.triggerData.priority) compare
    (this.triggerData.tick, this.triggerData.priority)
  override def toString: String = {
    "Trigger:" + triggerData.agent + "@" + triggerData.tick
  }
}
case class Initialize(override val triggerData: TriggerData) extends Trigger[Initialize] {
  override def copy(newId: Int) = Initialize(triggerData.copy(id = newId))
}
case class Transition(override val triggerData: TriggerData) extends Trigger[Transition] {
  override def copy(newId: Int) = Transition(triggerData.copy(id = newId))
}


