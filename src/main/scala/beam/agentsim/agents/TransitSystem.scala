package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{Actor, ActorContext, ActorLogging, ActorRef, OneForOneStrategy}
import akka.util.Timeout
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.model.BeamLeg
import beam.router.osm.TollCalculator
import beam.router.{BeamRouter, TransitInitializer}
import beam.sim.BeamScenario
import beam.sim.common.GeoUtils
import beam.utils.NetworkHelper
import com.conveyal.r5.transit.{RouteInfo, TransportNetwork}
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager

class TransitSystem(val beamScenario: BeamScenario, val scenario: Scenario, val transportNetwork: TransportNetwork, val scheduler: ActorRef, val parkingManager: ActorRef, val tollCalculator: TollCalculator, val geo: GeoUtils, val networkHelper: NetworkHelper, val eventsManager: EventsManager, val beamRouter: ActorRef) extends Actor with ActorLogging {

  override val supervisorStrategy: OneForOneStrategy =
    OneForOneStrategy(maxNrOfRetries = 0) {
      case _: Exception      => Stop
      case _: AssertionError => Stop
    }
  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)

  val initializer =
    new TransitInitializer(
      beamScenario.beamConfig,
      beamScenario.dates,
      beamScenario.vehicleTypes,
      transportNetwork,
      BeamRouter.oneSecondTravelTime
    )
  initDriverAgents(context, initializer, scheduler, parkingManager, initializer.initMap)
  log.info("Transit schedule has been initialized")

  override def receive: Receive = {
    case TriggerWithId(InitializeTrigger(_), triggerId) =>
      sender ! CompletionNotice(triggerId, Vector())
  }

  private def initDriverAgents(
                                context: ActorContext,
                                initializer: TransitInitializer,
                                scheduler: ActorRef,
                                parkingManager: ActorRef,
                                transits: Map[Id[BeamVehicle], (RouteInfo, Seq[BeamLeg])]
                              ) = {
    transits.foreach {
      case (tripVehId, (route, legs)) =>
        initializer.createTransitVehicle(tripVehId, route, legs).foreach { vehicle =>
          val transitDriverId = TransitDriverAgent.createAgentIdFromVehicleId(tripVehId)
          val transitDriverAgentProps = TransitDriverAgent.props(
            scheduler,
            beamScenario,
            transportNetwork,
            tollCalculator,
            eventsManager,
            parkingManager,
            transitDriverId,
            vehicle,
            legs,
            geo,
            networkHelper
          )
          val transitDriver = context.actorOf(transitDriverAgentProps, transitDriverId.toString)
          scheduler ! ScheduleTrigger(InitializeTrigger(0), transitDriver)
        }
    }
  }

}
