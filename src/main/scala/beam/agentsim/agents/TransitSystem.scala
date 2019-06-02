package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy}
import akka.util.Timeout
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.Modes
import beam.router.Modes.BeamMode.{BUS, CABLE_CAR, FERRY, GONDOLA, RAIL, SUBWAY, TRAM}
import beam.router.model.BeamLeg
import beam.router.osm.TollCalculator
import beam.sim.BeamScenario
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.utils.NetworkHelper
import beam.utils.logging.ExponentialLazyLogging
import com.conveyal.r5.transit.{RouteInfo, TransitLayer, TransportNetwork}
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.vehicles.Vehicle

import scala.io.Source
import scala.util.Try

class TransitSystem(
  val beamScenario: BeamScenario,
  val scenario: Scenario,
  val transportNetwork: TransportNetwork,
  val scheduler: ActorRef,
  val parkingManager: ActorRef,
  val tollCalculator: TollCalculator,
  val geo: GeoUtils,
  val networkHelper: NetworkHelper,
  val eventsManager: EventsManager
) extends Actor
    with ActorLogging {

  override val supervisorStrategy: OneForOneStrategy =
    OneForOneStrategy(maxNrOfRetries = 0) {
      case _: Exception      => Stop
      case _: AssertionError => Stop
    }
  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)

  initDriverAgents()
  log.info("Transit schedule has been initialized")

  override def receive: Receive = {
    case TriggerWithId(InitializeTrigger(_), triggerId) =>
      sender ! CompletionNotice(triggerId, Vector())
  }

  private def initDriverAgents(): Unit = {
    val initializer = new TransitVehicleInitializer(beamScenario.beamConfig, beamScenario.vehicleTypes)
    beamScenario.transitSchedule.foreach {
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

class TransitVehicleInitializer(val beamConfig: BeamConfig, val vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType]) extends ExponentialLazyLogging {

  private val transitVehicleTypesByRoute: Map[String, Map[String, String]] = loadTransitVehicleTypesMap()

  def createTransitVehicle(
                            transitVehId: Id[Vehicle],
                            route: RouteInfo,
                            legs: Seq[BeamLeg]
                          ): Option[BeamVehicle] = {
    val mode = Modes.mapTransitMode(TransitLayer.getTransitModes(route.route_type))
    val vehicleType = getVehicleType(route, mode)
    mode match {
      case BUS | SUBWAY | TRAM | CABLE_CAR | RAIL | FERRY | GONDOLA if vehicleType != null =>
        val powertrain = Option(vehicleType.primaryFuelConsumptionInJoulePerMeter)
          .map(new Powertrain(_))
          .getOrElse(Powertrain.PowertrainFromMilesPerGallon(Powertrain.AverageMilesPerGallon))

        val beamVehicleId = BeamVehicle.createId(transitVehId) //, Some(mode.toString)

        val vehicle: BeamVehicle = new BeamVehicle(
          beamVehicleId,
          powertrain,
          vehicleType
        ) // TODO: implement fuel level later as needed
        Some(vehicle)
      case _ =>
        logger.error("{} is not supported yet", mode)
        None
    }
  }

  def getVehicleType(route: RouteInfo, mode: Modes.BeamMode): BeamVehicleType = {
    val vehicleTypeId = Id.create(
      transitVehicleTypesByRoute
        .get(route.agency_id)
        .fold(None.asInstanceOf[Option[String]])(_.get(route.route_id))
        .getOrElse(mode.toString.toUpperCase + "-" + route.agency_id),
      classOf[BeamVehicleType]
    )

    if (vehicleTypes.contains(vehicleTypeId)) {
      vehicleTypes(vehicleTypeId)
    } else {
      logger.debug(
        "no specific vehicleType available for mode and transit agency pair '{}', using default vehicleType instead",
        vehicleTypeId.toString
      )
      //There has to be a default one defined
      vehicleTypes.getOrElse(
        Id.create(mode.toString.toUpperCase + "-DEFAULT", classOf[BeamVehicleType]),
        vehicleTypes(Id.create("TRANSIT-TYPE-DEFAULT", classOf[BeamVehicleType]))
      )
    }
  }

  private def loadTransitVehicleTypesMap() = {
    Try(
      Source
        .fromFile(beamConfig.beam.agentsim.agents.vehicles.transitVehicleTypesByRouteFile)
        .getLines()
        .toList
        .tail
    ).getOrElse(List())
      .map(_.trim.split(","))
      .filter(_.length > 2)
      .groupBy(_(0))
      .mapValues(_.groupBy(_(1)).mapValues(_.head(2)))
  }

}
