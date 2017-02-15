package beam.metasim.agents

import akka.actor.ActorRef

import scala.math.Ordered.orderingToOrdered

class TriggerData(val agent: ActorRef, val tick: Double, val priority: Int = 0, var id: Int = 0)
{
  require(tick>=0, "Negative timestamps not supported!")
}

abstract class Trigger() extends Ordered[Trigger] {
  val triggerData: TriggerData
  def compare(that: Trigger): Int = (that.triggerData.tick, that.triggerData.priority) compare
    (this.triggerData.tick, this.triggerData.priority)
  override def toString: String = {
    "Trigger:" + triggerData.agent + "@" + triggerData.tick
  }
}
case class Initialize(override val triggerData: TriggerData) extends Trigger
case class Transition(override val triggerData: TriggerData) extends Trigger

