package beam.sim

import akka.actor.{Actor, ActorRef, ActorSelection, Props, Terminated}
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.TransitDriverAgent.createAgentIdFromVehicleId
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.{InitializeTrigger, Population, TransitSystem}
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.router.{RouteHistory, TransitInitializer}
import beam.sim.DistributedSimulationPart.{Initialized, MasterBeamData}
import beam.sim.SimulationClusterManager.SimWorker
import com.conveyal.r5.profile.StreetMode
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.Id

/**
  * @author Dmitry Openkov
  */
class DistributedSimulationPart(
  partNumber: Int,
  total: Int,
  beamServices: BeamServices
) extends Actor
    with StrictLogging {

  override def receive: Receive = { case data: MasterBeamData =>
    logger.info(s"Got MasterBeamData sim-worker: $partNumber, total = $total")

    val oneSecondTravelTime = (_: Double, _: Int, _: StreetMode) => 1.0
    val transitSchedule = new TransitInitializer(
      beamServices.beamScenario.beamConfig,
      beamServices.geo,
      beamServices.beamScenario.dates,
      beamServices.beamScenario.transportNetwork,
      oneSecondTravelTime
    ).initMap

    val transitSystem = context.actorOf(
      Props(
        new TransitSystem(
          beamServices,
          beamServices.beamScenario,
          beamServices.matsimServices.getScenario,
          beamServices.beamScenario.transportNetwork,
          transitSchedule,
          data.scheduler,
          data.parkingNetworkManager,
          data.chargingNetworkManager,
          beamServices.tollCalculator,
          beamServices.geo,
          beamServices.networkHelper,
          beamServices.matsimServices.getEvents
        )
      ),
      "transit-system"
    )
    context.watch(transitSystem)
    data.scheduler ! ScheduleTrigger(InitializeTrigger(0), transitSystem)

    val transitAgentPaths: Map[Id[BeamVehicle], ActorSelection] = SimulationClusterManager
      .splitIntoParts(transitSchedule.keys, data.simWorkers.size)
      .zip(data.simWorkers)
      .flatMap { case (vehicleIds, simWorker) =>
        vehicleIds.map(transitVehicleId =>
          transitVehicleId -> context.actorSelection(
            simWorker.actorRef.path / "simulationPart" / "transit-system" /
            createAgentIdFromVehicleId(transitVehicleId).toString
          )
        )
      }
      .toMap

    val population = context.actorOf(
      Population.props(
        beamServices.matsimServices.getScenario,
        beamServices.beamScenario,
        beamServices,
        data.scheduler,
        beamServices.beamScenario.transportNetwork,
        transitAgentPaths,
        beamServices.tollCalculator,
        beamServices.beamRouter,
        data.rideHailManager,
        data.parkingNetworkManager,
        data.chargingNetworkManager,
        data.sharedVehicleFleets,
        beamServices.matsimServices.getEvents,
        new RouteHistory(beamServices.beamConfig)
      ),
      "population"
    )

    context.watch(population)

    data.scheduler ! ScheduleTrigger(InitializeTrigger(0), population)

    sender() ! Initialized(partNumber, beamServices.matsimServices.getIterationNumber)

    context.become(interationStarted(transitSystem, population))
  }

  private def interationStarted(transitSystem: ActorRef, population: ActorRef): Actor.Receive = { case Finish =>
    population ! Finish
    transitSystem ! Finish
    context.become(interationFinished())
  }

  private def interationFinished(): Actor.Receive = { case Terminated(x) =>
    logger.debug(s"Terminated {}", x)
    if (context.children.isEmpty) {
      context.stop(self)
    } else {
      logger.debug("Remaining: {}", context.children)
    }
  }
}

object DistributedSimulationPart {

  case class MasterBeamData(
    iteration: Int,
    scheduler: ActorRef,
    rideHailManager: ActorRef,
    parkingNetworkManager: ActorRef,
    chargingNetworkManager: ActorRef,
    sharedVehicleFleets: Seq[ActorRef],
    simWorkers: Seq[SimWorker]
  )

  case class Initialized(partNumber: Int, iteration: Int)

  def props(portionNum: Int, total: Int, beamServices: BeamServices): Props = {
    Props(new DistributedSimulationPart(portionNum, total, beamServices))
  }
}
