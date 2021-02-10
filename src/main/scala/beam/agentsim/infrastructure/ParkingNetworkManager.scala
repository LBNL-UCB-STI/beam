package beam.agentsim.infrastructure

import akka.actor.{ActorLogging, Cancellable}
import akka.event.Logging
import beam.agentsim.Resource.ReleaseParkingStall
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import beam.utils.metrics.SimpleCounter
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration._

class ParkingNetworkManager(beamServices: BeamServices, parkingNetworkInfo: ParkingNetworkInfo)
    extends beam.utils.CriticalActor
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

  private val publicParking = parkingNetworkInfo.getPublicParking
  private val isParallelizedPublicParking = publicParking.isInstanceOf[ParallelParkingManager]

  override def receive: Receive = {
    case inquiry: ParkingInquiry if isParallelizedPublicParking =>
      publicParking.processParkingInquiry(inquiry, Some(counter)).map(sender() ! _)
    case inquiry: ParkingInquiry      => publicParking.processParkingInquiry(inquiry, None).map(sender() ! _)
    case release: ReleaseParkingStall => parkingNetworkInfo.getPublicParking.processReleaseParkingStall(release)
    case "tick"                       => counter.tick()
  }

  override def postStop(): Unit = tickTask.cancel()
}

object ParkingNetworkManager extends LazyLogging {}
