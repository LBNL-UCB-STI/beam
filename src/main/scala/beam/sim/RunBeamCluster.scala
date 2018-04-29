package beam.sim

import java.nio.file.InvalidPathException

import akka.actor.{ActorSystem, PoisonPill, Props}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}
import beam.router.RouteFrontend
import beam.router.gtfs.FareCalculator
import beam.router.osm.TollCalculator
import beam.router.r5.NetworkCoordinator
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.utils.reflection.ReflectionUtils
import beam.utils.{BeamConfigUtils, FileUtils, LoggingUtil}
import com.conveyal.r5.streets.StreetLayer
import com.conveyal.r5.transit.TransportNetwork
import com.typesafe.config.ConfigFactory
import javassist.ClassPool
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Network
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.matsim.vehicles.Vehicle

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
