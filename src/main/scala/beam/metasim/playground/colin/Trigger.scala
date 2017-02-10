package beam.metasim.playground.colin

import akka.actor.ActorRef
import scala.math.Ordered.orderingToOrdered

class TriggerData(val agent: ActorRef, val tick: Double, val priority: Int = 0, var id: Int = 0)

abstract case class Trigger() extends Ordered[Trigger] {
  val data: TriggerData
  def compare(that: Trigger): Int = (that.data.tick, that.data.priority) compare
    (this.data.tick, this.data.priority)
  override def toString: String = {
    data.agent + "::" + "@" + data.tick
  }
}
case class Initialize(override val data: TriggerData) extends Trigger
case class Transition(override val data: TriggerData) extends Trigger

