package beam.agentsim.infrastructure

import akka.actor.{ActorLogging, Cancellable, Props}
import akka.event.Logging
import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.infrastructure.parking.ParkingNetwork
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import beam.utils.logging.LoggingMessageActor
import beam.utils.metrics.SimpleCounter
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration._

class ParkingNetworkManager(beamServices: BeamServices, parkingNetworkMap: ParkingNetwork[_])
    extends beam.utils.CriticalActor
    with LoggingMessageActor
    with ActorLogging {
  import beamServices._
  private val beamConfig: BeamConfig = beamScenario.beamConfig

  private val counter = {
    val displayPerformanceTimings = beamConfig.beam.agentsim.taz.parkingManager.displayPerformanceTimings
    val logLevel = if (displayPerformanceTimings) Logging.InfoLevel else Logging.DebugLevel
    new SimpleCounter(log, logLevel, "Receiving {} per seconds of ParkingInquiry for {}")
  }

  private val tickTask: Cancellable =
    context.system.scheduler.scheduleWithFixedDelay(2.seconds, 10.seconds, self, "tick")(context.dispatcher)

  override def loggedReceive: Receive = {
    case inquiry: ParkingInquiry if beamConfig.beam.agentsim.taz.parkingManager.method == "PARALLEL" =>
      parkingNetworkMap.processParkingInquiry(inquiry, Some(counter)).map(sender() ! _)
    case inquiry: ParkingInquiry =>
      parkingNetworkMap.processParkingInquiry(inquiry).map(sender() ! _)
    case release: ReleaseParkingStall =>
      parkingNetworkMap.processReleaseParkingStall(release)
    case "tick" => counter.tick()
  }

  override def postStop(): Unit = tickTask.cancel()
}

object ParkingNetworkManager extends LazyLogging {

  def props(services: BeamServices, parkingNetworkMap: ParkingNetwork[_]): Props = {
    Props(new ParkingNetworkManager(services, parkingNetworkMap))
  }
}
