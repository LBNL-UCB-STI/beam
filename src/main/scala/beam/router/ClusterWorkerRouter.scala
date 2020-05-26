package beam.router

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.routing.FromConfig
import com.typesafe.config.Config

class ClusterWorkerRouter(config: Config) extends Actor with ActorLogging {

  // This router is used both with lookup and deploy of routees. If you
  // have a router with only lookup of routees you can use Props.empty
  // instead of Props[StatsWorker.class].

  val workerRouter: ActorRef = context.actorOf(
    FromConfig.props(Props(classOf[RoutingWorker], config)),
    name = "workerRouter"
  )
  def getNameAndHashCode: String = s"ClusterWorkerRouter[${hashCode()}], Path: `${self.path}`"
  log.info("{} inited. workerRouter => {}", getNameAndHashCode, workerRouter)

  def receive: Receive = {
    case other =>
      log.debug("{} received {}", getNameAndHashCode, other)
      workerRouter.forward(other)
  }
}
