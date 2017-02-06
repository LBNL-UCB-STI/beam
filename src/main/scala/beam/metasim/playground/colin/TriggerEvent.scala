package beam.metasim.playground.colin

import akka.actor.ActorRef
import org.matsim.core.controler.events.ControlerEvent
import org.matsim.core.controler.events.StartupEvent

case class TriggerEvent(agent: ActorRef, tick: Double, trigger: Trigger, priority: Int) extends Ordered[TriggerEvent] {
  import scala.math.Ordered.orderingToOrdered
  def compare(that: TriggerEvent): Int = (that.tick, that.priority) compare (this.tick, this.priority) 
  override def toString: String = {
    agent + "::" + trigger + "@" + tick
  }
}

sealed trait Event
trait ActorSimEvent extends Event
trait MATSimEvent[E<: ControlerEvent] extends Event

