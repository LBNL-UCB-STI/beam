package beam.metasim.playground.colin

import akka.actor.ActorRef

case class BeamEvent(agent: ActorRef, tick: Double, msg: String, priority: Int) extends Ordered[BeamEvent] {
  import scala.math.Ordered.orderingToOrdered
  def compare(that: BeamEvent): Int = (that.tick, that.priority) compare (this.tick, this.priority) 
  override def toString: String = {
    agent + "::" + msg + "@" + tick
  }
}