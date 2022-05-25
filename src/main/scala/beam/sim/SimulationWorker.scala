package beam.sim

import akka.actor.{Actor, ActorRef, Props, Terminated}
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.{InitializeTrigger, Population}
import beam.agentsim.events.eventbuilder.EventBuilderActor.{EventBuilderActorCompleted, FlushEvents}
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.router.RouteHistory
import beam.sim.SimulationWorker.MasterBeamData
import com.typesafe.scalalogging.StrictLogging

/**
  * @author Dmitry Openkov
  */
class SimulationWorker(
  portionNum: Int,
  total: Int,
  beamServices: BeamServices
) extends Actor
    with StrictLogging {

  override def receive: Receive = { case data: MasterBeamData =>
    logger.info(s"Got MasterBeamData sim-worker: $portionNum, total = $total")
    val population = context.actorOf(
      Population.props( //todome simulate only some of persons
        beamServices.matsimServices.getScenario,
        beamServices.beamScenario,
        beamServices,
        data.scheduler,
        beamServices.beamScenario.transportNetwork,
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

    context.become(interationStarted(population))
  }

  private def interationStarted(population: ActorRef): Actor.Receive = { case Finish =>
    population ! Finish
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

object SimulationWorker {

  case class MasterBeamData(
    scheduler: ActorRef,
    rideHailManager: ActorRef,
    parkingNetworkManager: ActorRef,
    chargingNetworkManager: ActorRef,
    sharedVehicleFleets: Seq[ActorRef]
  )

  def props(portionNum: Int, total: Int, beamServices: BeamServices): Props = {
    Props(new SimulationWorker(portionNum, total, beamServices))
  }
}
