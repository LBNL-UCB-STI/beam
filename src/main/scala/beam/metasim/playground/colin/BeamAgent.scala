package beam.metasim.playground.colin

import akka.actor.Actor
import akka.event.Logging

class BeamAgent extends Actor {
  val log = Logging(context.system, this)
  def receive = {
    case "hi" ⇒ log.info("saying hi")
    case "bye" ⇒ log.info("saying bye")
    case _      ⇒ log.info("received unknown message")
  }
}