package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{Actor, ActorContext, ActorLogging, ActorRef, OneForOneStrategy}
import akka.util.Timeout
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.router.model.BeamLeg
import beam.router.osm.TollCalculator
import beam.router.{BeamRouter, TransitInitializer}
import beam.sim.BeamScenario
import beam.sim.common.GeoUtils
import beam.utils.NetworkHelper
import com.conveyal.r5.transit.{RouteInfo, TransportNetwork}
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.vehicles.Vehicle

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

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
      scenario.getTransitVehicles,
      BeamRouter.oneSecondTravelTime
    )
  val transits = initializer.initMap
  val agencyAndRouteByVehicleIds = initDriverAgents(context, initializer, scheduler, parkingManager, transits)
  log.info("Transit schedule has been initialized")

  override def receive: Receive = {
    case "Wurst" =>
  }

  private def initDriverAgents(
                                context: ActorContext,
                                initializer: TransitInitializer,
                                scheduler: ActorRef,
                                parkingManager: ActorRef,
                                transits: Map[Id[BeamVehicle], (RouteInfo, Seq[BeamLeg])]
                              ) = {
    val agencyAndRouteByVehicleIds: mutable.Map[Id[Vehicle], (String, String)] = TrieMap()
    transits.foreach {
      case (tripVehId, (route, legs)) =>
        initializer.createTransitVehicle(tripVehId, route, legs).foreach { vehicle =>
          agencyAndRouteByVehicleIds += (Id
            .createVehicleId(tripVehId.toString) -> (route.agency_id, route.route_id))
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
    agencyAndRouteByVehicleIds.toMap
  }

}
