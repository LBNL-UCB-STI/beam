package beam.sim

import java.nio.file.InvalidPathException

import akka.actor.{ActorSystem, PoisonPill, Props}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}
import beam.router.RouteFrontend
import beam.utils.BeamConfigUtils
import com.typesafe.config.ConfigFactory

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

  def parseArgs() = {

    args.sliding(2, 2).toList.foreach { r =>
      println(r.mkString(" "))
    }

    args.sliding(2, 2).toList.collect {
      case Array("--config", configName: String) if configName.trim.nonEmpty => ("config", configName)
      case Array("--cluster-port", value: String)  => ("cluster-port", value)
      case arg@_ => throw new IllegalArgumentException(arg.mkString(" "))
    }.toMap
  }

  val argsMap = parseArgs()

  var (config, cfgFile) = argsMap.get("config") match {
    case Some(fileName) =>
      (BeamConfigUtils.parseFileSubstitutingInputDirectory(fileName), fileName)
    case _ =>
      throw new InvalidPathException("null", "invalid configuration file.")
  }


  var clusterPort = argsMap.get("cluster-port").get
  config =
    ConfigFactory.parseString(s"""
          akka.remote.netty.tcp.port=${clusterPort}
          akka.remote.artery.canonical.port=$clusterPort
          """).withFallback(config)

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
