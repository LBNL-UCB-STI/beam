package beam.metasim.playground.colin

import akka.actor.ActorRef
import org.matsim.core.controler.events.ControlerEvent
import org.matsim.core.controler.events.StartupEvent

case class BeamEvent(agent: ActorRef, tick: Double, msg: Command, priority: Int) extends Ordered[BeamEvent] {
  import scala.math.Ordered.orderingToOrdered
  def compare(that: BeamEvent): Int = (that.tick, that.priority) compare (this.tick, this.priority) 
  override def toString: String = {
    agent + "::" + msg + "@" + tick
  }
}

sealed trait Event
trait ActorSimEvent extends Event
trait MATSimEvent[E<: ControlerEvent] extends Event

