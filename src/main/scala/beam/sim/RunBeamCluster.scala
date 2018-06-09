package beam.sim

import akka.actor.{Actor, ActorLogging, ActorSystem, DeadLetter, PoisonPill, Props}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}
import beam.router.BeamRouter.RoutingResponse
import beam.router.RouteFrontend
import com.typesafe.config.ConfigFactory
import kamon.Kamon
import kamon.prometheus.PrometheusReporter


object RunBeamCluster extends BeamHelper with App {
  print(
    """
  ________
  ___  __ )__________ _______ ___
  __  __  |  _ \  __ `/_  __ `__ \
  _  /_/ //  __/ /_/ /_  / / / / /
  /_____/ \___/\__,_/ /_/ /_/ /_/

 _____________________________________

 """)

  val argsMap = parseArgs(args)
  val config = Some(getConfig(argsMap)) match {
    case Some(cfg) =>
      ConfigFactory.parseString(
        s"""
           |akka.cluster.roles = [compute]
           |akka.actor.deployment {
           |      /statsService/singleton/workerRouter {
           |        router = round-robin-pool
           |        cluster {
           |          enabled = on
           |          max-nr-of-instances-per-node = 1
           |          allow-local-routees = on
           |          use-roles = ["compute"]
           |        }
           |      }
           |    }
          """.stripMargin)
        .withFallback(cfg)
  }

  Kamon.reconfigure(config.withFallback(ConfigFactory.defaultReference()))
  Kamon.addReporter(new PrometheusReporter())
  // Kamon.addReporter(new ZipkinReporter())

  val system = ActorSystem("ClusterSystem", config)

  system.actorOf(ClusterSingletonManager.props(
    singletonProps = Props(classOf[RouteFrontend], config),
    terminationMessage = PoisonPill,
    settings = ClusterSingletonManagerSettings(system).withRole("compute")),
    name = "statsService")

  system.actorOf(ClusterSingletonProxy.props(singletonManagerPath = "/user/statsService",
    settings = ClusterSingletonProxySettings(system).withRole("compute")),
    name = "statsServiceProxy")


  private val replayer = system.actorOf(DeadLetterReplayer.props())
  system.eventStream.subscribe(replayer, classOf[DeadLetter])

  logger.info("Exiting BEAM")
}

class DeadLetterReplayer extends Actor with ActorLogging {
//  val sr = SerializationExtension(context.system)
//
//  private val msgSize: mutable.ArrayBuffer[Double] = mutable.ArrayBuffer.empty[Double]


  override def receive: Receive = {
    case d:DeadLetter =>
      d.message match {
        case r: RoutingResponse =>
          d.recipient.tell(d.message, sender)
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