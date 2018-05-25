package beam.router

import akka.actor.{Actor, ActorLogging, DeadLetter, Props}
import akka.routing.{Broadcast, FromConfig}
import beam.router.BeamRouter.{InitTransit_v2, RoutingResponse, TransitInited}
import beam.router.r5.R5RoutingWorker_v2
import com.typesafe.config.Config

class RouteFrontend(config: Config) extends Actor with ActorLogging{
  // This router is used both with lookup and deploy of routees. If you
  // have a router with only lookup of routees you can use Props.empty
  // instead of Props[StatsWorker.class].
  val workerRouter = context.actorOf(FromConfig.props(Props(classOf[R5RoutingWorker_v2], config)),
    name = "workerRouter")


  def getNameAndHashCode: String = s"RouteFrontend[${hashCode()}], Path: `${self.path}`"


  log.info("{} inited. workerRouter => {}", getNameAndHashCode, workerRouter)

  def receive = {
    case d:DeadLetter =>
      d.message match {
        case r: RoutingResponse =>
          log.info(s"DeadLetter with '{}'. Resend {} => {}", r, d.recipient.path, d.sender.path)
          d.recipient.tell(d.message, sender)
      }
    // We have to send TransitInited as Broadcast because our R5RoutingWorker is stateful!
    case transitInited: TransitInited =>
      log.info("{} Sending Broadcast", getNameAndHashCode)
      workerRouter.tell(Broadcast(transitInited), sender())
    // We have to send TransitInited as Broadcast because our R5RoutingWorker is stateful!
    case initTransit_v2: InitTransit_v2 =>
      log.info("{} Sending Broadcast", getNameAndHashCode)
      workerRouter.tell(Broadcast(initTransit_v2), sender())
    case other =>
      log.debug("{} received {}", getNameAndHashCode, other)
      workerRouter.forward(other)
  }
}
