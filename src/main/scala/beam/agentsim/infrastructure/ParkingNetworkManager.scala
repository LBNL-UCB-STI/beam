package beam.agentsim.infrastructure

import akka.actor.{ActorLogging, ActorRef, Cancellable, Props}
import akka.event.Logging
import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.events.LeavingParkingEvent
import beam.agentsim.infrastructure.parking.ParkingNetwork
import beam.sim.BeamServices
import beam.utils.logging.LoggingMessageActor
import beam.utils.metrics.SimpleCounter
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.core.network.NetworkUtils

import scala.concurrent.duration._

class ParkingNetworkManager(beamServices: BeamServices, parkingNetworkMap: ParkingNetwork)
    extends beam.utils.CriticalActor
    with LoggingMessageActor
    with ActorLogging {
  import beamServices._
  private val agentSimConfig = beamScenario.beamConfig.beam.agentsim

  private val counter = {
    val displayPerformanceTimings = agentSimConfig.taz.parkingManager.displayPerformanceTimings
    val logLevel = if (displayPerformanceTimings) Logging.InfoLevel else Logging.DebugLevel
    new SimpleCounter(log, logLevel, "Receiving {} per seconds of ParkingInquiry for {}")
  }

  private val tickTask: Cancellable =
    context.system.scheduler.scheduleWithFixedDelay(2.seconds, 10.seconds, self, "tick")(context.dispatcher)

  override def loggedReceive: Receive = {
    case inquiry: ParkingInquiry =>
      val resolvedParkingDuration = Math.max(inquiry.parkingDuration, agentSimConfig.schedulerParallelismWindow)
      val desirableMinimumParkingDuration =
        Math.max(resolvedParkingDuration, agentSimConfig.agents.parking.estimatedMinParkingDurationInSeconds)
      val fixedInquiry = inquiry.copy(parkingDuration = desirableMinimumParkingDuration)
      sender() ! parkingNetworkMap.processParkingInquiry(
        fixedInquiry,
        parallelizationCounterOption =
          if (agentSimConfig.taz.parkingManager.method == "PARALLEL") Some(counter) else None
      )
    case release: ReleaseParkingStall =>
      parkingNetworkMap.processReleaseParkingStall(release)
    case "tick" => counter.tick()
  }

  override def postStop(): Unit = tickTask.cancel()
}

object ParkingNetworkManager extends LazyLogging {

  def props(services: BeamServices, parkingNetworkMap: ParkingNetwork): Props = {
    Props(new ParkingNetworkManager(services, parkingNetworkMap))
  }

  private def calculateScore(
    cost: Double,
    energyCharge: Double
  ): Double = -cost - energyCharge

  def handleReleasingParkingSpot(
    tick: Int,
    currentBeamVehicle: BeamVehicle,
    energyChargedMaybe: Option[Double],
    driver: Id[_],
    parkingManager: ActorRef,
    beamServices: BeamServices
  ): Unit = {
    val stallForLeavingParkingEventMaybe = currentBeamVehicle.stall match {
      case Some(stall) =>
        parkingManager ! ReleaseParkingStall(stall, tick)
        currentBeamVehicle.unsetParkingStall()
        Some(stall)
      case None if currentBeamVehicle.lastUsedStall.isDefined =>
        // This can now happen if a vehicle was charging and released the stall already
        Some(currentBeamVehicle.lastUsedStall.get)
      case None =>
        None
    }
    stallForLeavingParkingEventMaybe.foreach { stall =>
      val vehicleActivityData = BeamVehicle.collectVehicleActivityData(
        tick,
        Right(stall.link.getOrElse(NetworkUtils.getNearestLink(beamServices.beamScenario.network, stall.locationUTM))),
        currentBeamVehicle.beamVehicleType,
        None,
        Some(stall),
        beamServices
      )
      val emissionsProfile =
        currentBeamVehicle.emitEmissions(vehicleActivityData, classOf[LeavingParkingEvent], beamServices)
      val energyCharge: Double = energyChargedMaybe.getOrElse(0.0)
      val score = calculateScore(stall.costInDollars, energyCharge)
      beamServices.matsimServices.getEvents.processEvent(
        LeavingParkingEvent(
          tick,
          stall,
          score,
          driver.toString,
          currentBeamVehicle.id,
          emissionsProfile
        )
      )
    }
  }
}
