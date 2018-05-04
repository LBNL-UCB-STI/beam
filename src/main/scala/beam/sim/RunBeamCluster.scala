package beam.sim

import java.nio.file.InvalidPathException

import akka.actor.{ActorSystem, PoisonPill, Props}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}
import beam.router.RouteFrontend
import beam.utils.BeamConfigUtils
import com.typesafe.config.{Config, ConfigFactory}

import scala.collection.JavaConverters._

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

  val system = ActorSystem("ClusterSystem", config)

  system.actorOf(ClusterSingletonManager.props(
    singletonProps = Props(classOf[RouteFrontend], config),
    terminationMessage = PoisonPill,
    settings = ClusterSingletonManagerSettings(system).withRole("compute")),
    name = "statsService")

  system.actorOf(ClusterSingletonProxy.props(singletonManagerPath = "/user/statsService",
    settings = ClusterSingletonProxySettings(system).withRole("compute")),
    name = "statsServiceProxy")

  logger.info("Exiting BEAM")
}
