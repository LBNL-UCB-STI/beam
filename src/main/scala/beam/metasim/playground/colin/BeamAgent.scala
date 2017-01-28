package beam.metasim.playground.colin

import akka.actor.Actor
import akka.event.Logging
import akka.actor.Props

object BeamAgent {
  def props(id: Int): Props = Props(new BeamAgent(id))
}
class BeamAgent(id: Int) extends Actor {
  val log = Logging(context.system, this)
  def receive = {
    case "hi" ⇒ log.info("saying hi")
    case "bye" ⇒ log.info("saying bye")
    case _      ⇒ log.info("received unknown message")
  }
  override def toString: String = {
    this.id.toString()
  }
}