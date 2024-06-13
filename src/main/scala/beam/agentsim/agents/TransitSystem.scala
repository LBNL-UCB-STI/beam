package beam.agentsim.agents

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{ActorLogging, ActorRef, OneForOneStrategy, Terminated}
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.TransitVehicleInitializer.loadGtfsVehicleTypes
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, VehicleManager}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.Modes.BeamMode.{BUS, CABLE_CAR, FERRY, GONDOLA, RAIL, SUBWAY, TRAM}
import beam.router.model.BeamLeg
import beam.router.osm.TollCalculator
import beam.router.Modes
import beam.sim.cluster.SimulationClusterManager
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.sim.{BeamScenario, BeamServices}
import beam.utils.csv.GenericCsvReader
import beam.utils.logging.{ExponentialLazyLogging, LoggingMessageActor}
import beam.utils.NetworkHelper
import com.conveyal.r5.transit.{RouteInfo, TransitLayer, TransportNetwork}
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.vehicles.Vehicle

import java.util.concurrent.atomic.AtomicReference
import scala.util.Random

class TransitSystem(
  val beamServices: BeamServices,
  val beamScenario: BeamScenario,
  val scenario: Scenario,
  val transportNetwork: TransportNetwork,
  val transitSchedule: Map[Id[BeamVehicle], (RouteInfo, Array[BeamLeg])],
  val scheduler: ActorRef,
  val parkingManager: ActorRef,
  val chargingNetworkManager: ActorRef,
  val tollCalculator: TollCalculator,
  val geo: GeoUtils,
  val networkHelper: NetworkHelper,
  val eventsManager: EventsManager
) extends LoggingMessageActor
    with ActorLogging {

  override val supervisorStrategy: OneForOneStrategy =
    OneForOneStrategy(maxNrOfRetries = 0) {
      case _: Exception      => Stop
      case _: AssertionError => Stop
    }

  initDriverAgents()
  log.info("Transit schedule has been initialized")

  override def loggedReceive: PartialFunction[Any, Unit] = {
    case TriggerWithId(InitializeTrigger(_), triggerId) =>
      sender ! CompletionNotice(triggerId, Vector())
    case Terminated(_) =>
    // Do nothing
    case Finish =>
      context.children.foreach(_ ! Finish)
      dieIfNoChildren()
      contextBecome { case Terminated(_) =>
        dieIfNoChildren()
      }
  }

  def dieIfNoChildren(): Unit = {
    if (context.children.isEmpty) {
      context.stop(self)
    } else {
      log.debug("Remaining: {}", context.children)
    }
  }

  private def initDriverAgents(): Unit = {
    val initializer = new TransitVehicleInitializer(beamScenario.beamConfig, beamScenario.vehicleTypes)
    val rand = new Random(beamScenario.beamConfig.matsim.modules.global.randomSeed)
    val partNumber = beamServices.originalConfig.beam.cluster.partNumber
    val totalParts = beamServices.originalConfig.beam.cluster.totalParts
    SimulationClusterManager.getPart(transitSchedule, partNumber, totalParts).foreach {
      case (tripVehId, (route, legs)) =>
        initializer.createTransitVehicle(tripVehId, route, rand.nextInt()).foreach { vehicle =>
          val transitDriverId = TransitDriverAgent.createAgentIdFromVehicleId(tripVehId)
          val transitDriverAgentProps = TransitDriverAgent.props(
            scheduler,
            beamServices,
            beamScenario,
            transportNetwork,
            tollCalculator,
            eventsManager,
            parkingManager,
            chargingNetworkManager,
            transitDriverId,
            vehicle,
            legs,
            geo,
            networkHelper
          )
          val transitDriver = context.actorOf(transitDriverAgentProps, transitDriverId.toString)
          context.watch(transitDriver)
          scheduler ! ScheduleTrigger(InitializeTrigger(0), transitDriver)
        }
    }
  }
}

object TransitSystem {}

class TransitVehicleInitializer(val beamConfig: BeamConfig, val vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType])
    extends ExponentialLazyLogging {

  //contains entries: agencyId -> Map[(routeId, Option(transitVehicleId)) -> VehicleType]
  //if an Option is empty that vehicle type works for entire route
  private val transitVehicleTypesByRoute: Map[String, Map[(String, Option[Id[BeamVehicle]]), String]] =
    loadTransitVehicleTypesMap()

  def createTransitVehicle(
    transitVehId: Id[Vehicle],
    route: RouteInfo,
    randomSeed: Int
  ): Option[BeamVehicle] = {
    val mode = Modes.mapTransitMode(TransitLayer.getTransitModes(route.route_type))
    val vehicleType = getVehicleType(route, transitVehId, mode)
    mode match {
      case BUS | SUBWAY | TRAM | CABLE_CAR | RAIL | FERRY | GONDOLA if vehicleType != null =>
        val powertrain = Powertrain(Option(vehicleType.primaryFuelConsumptionInJoulePerMeter))

        val beamVehicleId = BeamVehicle.createId(transitVehId) //, Some(mode.toString)

        val vehicle: BeamVehicle = new BeamVehicle(
          beamVehicleId,
          powertrain,
          vehicleType,
          new AtomicReference(VehicleManager.NoManager.managerId),
          randomSeed = randomSeed
        ) // TODO: implement fuel level later as needed
        Some(vehicle)
      case _ =>
        logger.error("{} is not supported yet", mode)
        None
    }
  }

  def getVehicleType(route: RouteInfo, transitVehId: Id[Vehicle], mode: Modes.BeamMode): BeamVehicleType = {
    val vehicleTypeId = Id.create(
      transitVehicleTypesByRoute
        .get(route.agency_id)
        .fold(Option.empty[String]) { vehicleTypes =>
          vehicleTypes
            .get(route.route_id -> Some(transitVehId))
            .orElse(vehicleTypes.get(route.route_id -> None))
        }
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
        TransitVehicleInitializer.transitModeToBeamVehicleType(mode),
        vehicleTypes(Id.create("TRANSIT-TYPE-DEFAULT", classOf[BeamVehicleType]))
      )
    }
  }

  private def loadTransitVehicleTypesMap(): Map[String, Map[(String, Option[Id[BeamVehicle]]), String]] = {
    val file = beamConfig.beam.agentsim.agents.vehicles.transitVehicleTypesByRouteFile
    loadGtfsVehicleTypes(file)
  }
}

object TransitVehicleInitializer {

  def loadGtfsVehicleTypes(file: String): Map[String, Map[(String, Option[Id[BeamVehicle]]), String]] = {
    case class VehicleTypeRow(
      agencyId: String,
      routeId: String,
      transitVehicleId: Option[Id[BeamVehicle]],
      vehicleTypeId: String
    )
    val rows: IndexedSeq[VehicleTypeRow] = GenericCsvReader.readAsSeq[VehicleTypeRow](file) { rec =>
      VehicleTypeRow(
        rec.get("agencyId"),
        rec.get("routeId"),
        Option(rec.get("tripId")).map(gtfsTripIdToBeamVehicleId),
        rec.get("vehicleTypeId")
      )
    }
    rows
      .groupBy(_.agencyId)
      .mapValues(_.groupBy(row => row.routeId -> row.transitVehicleId).mapValues(_.head.vehicleTypeId))
  }

  def gtfsTripIdToBeamVehicleId(tripId: String): Id[BeamVehicle] = {
    Id.create(BeamVehicle.noSpecialChars(tripId), classOf[BeamVehicle])
  }

  def transitModeToBeamVehicleType(mode: Modes.BeamMode): Id[BeamVehicleType] = {
    Id.create(mode.toString.toUpperCase + "-DEFAULT", classOf[BeamVehicleType])
  }
}
