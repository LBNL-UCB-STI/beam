package beam.router

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern.{ask, pipe}
import akka.routing.FromConfig
import akka.util.Timeout
import com.typesafe.config.Config

import scala.concurrent.duration._

object ClusterWorkerRouter {
  case object ReadyCheck
}

class ClusterWorkerRouter(config: Config) extends Actor with ActorLogging {
  import ClusterWorkerRouter._
  import context.dispatcher

  private implicit val readyCheckTimeout: Timeout = Timeout(10.seconds)

  private val workerRouter: ActorRef = context.actorOf(
    FromConfig.props(RoutingWorker.propsFromConfig(config)),
    name = "workerRouter"
  )

  private def getNameAndHashCode: String = s"ClusterWorkerRouter[${hashCode()}], Path: `${self.path}`"

  log.info("{} inited. workerRouter => {}", getNameAndHashCode, workerRouter)

  override def receive: Receive = {
    case ReadyCheck =>
      (workerRouter ? BeamRouter.WorkAvailable)
        .map(_ == BeamRouter.GimmeWork)
        .recover { case _ => false }
        .pipeTo(sender())
    case other =>
      log.debug("{} received {}", getNameAndHashCode, other)
      workerRouter.forward(other)
  }
}
