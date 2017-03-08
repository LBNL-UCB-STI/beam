package beam.agentsim.agents

import akka.actor.ActorRef

import scala.math.Ordered.orderingToOrdered

case class TriggerData(val agent: ActorRef, val tick: Double, val priority: Int = 0, val id: Long = 0 ) {
  require(tick>=0, "Negative timestamps not supported!")
}

case class Initialize(val triggerData: TriggerData) extends Trigger[Initialize] {
  def withId(newId:Long) = this.copy(triggerData.copy(id = newId))
}

case class Transition(val triggerData: TriggerData) extends Trigger[Transition] {
  def withId(newId:Long) = this.copy(triggerData.copy(id = newId))
}

trait Trigger[T <: Trigger[T]] {
  // self-typing to T to force withId to return this type
  def withId(id: Long): T
  def triggerData: TriggerData
  def compare(that: Trigger[T]): Int = (that.triggerData.tick, that.triggerData.priority) compare
    (this.triggerData.tick, this.triggerData.priority)
}

