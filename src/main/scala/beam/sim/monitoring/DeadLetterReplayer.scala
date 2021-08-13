package beam.sim.monitoring

import akka.actor.{Actor, ActorLogging, DeadLetter, Props}
import beam.router.BeamRouter.RoutingResponse

class DeadLetterReplayer extends Actor with ActorLogging {

  override def receive: Receive = {
    case d: DeadLetter =>
      d.message match {
        case r: RoutingResponse =>
          log.debug("Retrying {}", r)
          d.recipient.tell(d.message, sender)
        case beam.router.BeamRouter.GimmeWork => //Do not retry GimmeWork - resiliency is built in
        case _ =>
          log.error(s"DeadLetter. Don't know what to do with: $d")
      }
    case other =>
      log.error(s"Don't know what to do with: $other")
  }
}

object DeadLetterReplayer {

  def props(): Props = {
    Props(new DeadLetterReplayer())
  }
}
