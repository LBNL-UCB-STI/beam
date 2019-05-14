package beam.sim.vehiclesharing

import java.util.concurrent.TimeUnit

import akka.actor.Status.Success
import akka.actor.{Actor, ActorLogging, ActorRef, Stash}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import beam.agentsim.Resource.{Boarded, NotAvailable, NotifyVehicleIdle, TryToBoardVehicle}
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.agents.household.HouseholdActor.{MobilityStatusInquiry, MobilityStatusResponse, ReleaseVehicle, ReleaseVehicleAndReply}
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.Token
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.ParkingManager.{ParkingInquiry, ParkingInquiryResponse}
import beam.agentsim.infrastructure.ParkingStall.NoNeed
import beam.agentsim.scheduler.BeamAgentScheduler.CompletionNotice
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.BeamSkimmer
import beam.sim.BeamServices
import beam.sim.population.AttributesOfIndividual
import com.vividsolutions.jts.geom.{Coordinate, Envelope}
import com.vividsolutions.jts.index.quadtree.Quadtree
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.geometry.CoordUtils

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

private[vehiclesharing] class FixedNonReservingFleetManager(
  val parkingManager: ActorRef,
  val locations: Iterable[Coord],
  val vehicleType: BeamVehicleType,
  val mainScheduler: ActorRef,
  val beamServices: BeamServices,
  val beamSkimmer: BeamSkimmer
) extends Actor
    with ActorLogging
    with Stash
    with RepositionManager {

  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)
  private implicit val executionContext: ExecutionContext = context.dispatcher

  private val vehicles = (locations.zipWithIndex map {
    case (location, ix) =>
      val vehicle = new BeamVehicle(
        Id.createVehicleId(self.path.name + "-" + ix),
        new Powertrain(0.0),
        vehicleType
      )
      vehicle.manager = Some(self)
      vehicle.spaceTime = SpaceTime(location, 0)
      vehicle.id -> vehicle
  }).toMap

  private val availableVehicles = mutable.Map[Id[BeamVehicle], BeamVehicle]()
  private val availableVehiclesIndex = new Quadtree

  override def receive: Receive = super[RepositionManager].receive orElse { // Reposition
    case TriggerWithId(InitializeTrigger(tick), triggerId) =>
      // Pipe my cars through the parking manager
      // and complete initialization only when I got them all.
      Future
        .sequence(vehicles.values.map { veh =>
          veh.manager = Some(self)
          parkingManager ? parkingInquiry(veh.spaceTime) flatMap {
            case ParkingInquiryResponse(stall, _) =>
              veh.useParkingStall(stall)
              self ? ReleaseVehicleAndReply(veh)
          }
        })
        .map(_ => CompletionNotice(triggerId, Vector()))
        .pipeTo(sender())

    case MobilityStatusInquiry(personId, whenWhere, _) =>
      // Search box: 1000 meters around query location
      val boundingBox = new Envelope(new Coordinate(whenWhere.loc.getX, whenWhere.loc.getY))
      boundingBox.expandBy(5000.0)

      val nearbyVehicles = availableVehiclesIndex.query(boundingBox).asScala.toVector.asInstanceOf[Vector[BeamVehicle]]
      nearbyVehicles.sortBy(veh => CoordUtils.calcEuclideanDistance(veh.spaceTime.loc, whenWhere.loc))
      sender ! MobilityStatusResponse(nearbyVehicles.take(5).map { vehicle =>
        Token(vehicle.id, self, vehicle.toStreetVehicle)
      })
      self ! REPVehicleInquiry(personId, whenWhere)

    case TryToBoardVehicle(token, who) =>
      println(s"to board ===> ${token.id}")
      makeUnavailable(token.id, token.streetVehicle) match {
        case Some(vehicle) => who ! Boarded(vehicle)
          println(s"available ===> ${token.id} | size: ${availableVehiclesIndex.size()}")
        case _ => who ! NotAvailable
          println(s"not available ===> ${token.id}")
      }

    case NotifyVehicleIdle(vId, whenWhere, _, _, _) =>
      makeTeleport(vId.asInstanceOf[Id[BeamVehicle]], whenWhere)

    case ReleaseVehicle(vehicle) =>
      makeAvailable(vehicle.id)

    case ReleaseVehicleAndReply(vehicle, _) =>
      makeAvailable(vehicle.id)
      sender() ! Success
  }

  def parkingInquiry(whenWhere: SpaceTime) = ParkingInquiry(
    whenWhere.loc,
    whenWhere.loc,
    "wherever",
    AttributesOfIndividual.EMPTY,
    NoNeed,
    0,
    0
  )

  override def getId: Id[VehicleManager] = Id.create("FixedNonReservingFleetManager", classOf[VehicleManager])
  override def getAvailableVehiclesIndex: Quadtree = availableVehiclesIndex
  override def getActorRef: ActorRef = self
  override def getScheduler: ActorRef = mainScheduler
  override def getBeamServices: BeamServices = beamServices
  override def getAlgorithm: RepositionAlgorithm = algorithm
  override def getTimeStep: Int = 15 * 60
  override def getBeamSkimmer: BeamSkimmer = beamSkimmer
  override def getRepositionManagerListenerInstance: RepositionManagerListener =
    getBeamServices.injector.getInstance(classOf[RepositionManagerListener])

  val algorithm = new AvailabilityBasedRepositioning(beamSkimmer, beamServices)

  override def makeAvailable(vehId: Id[BeamVehicle]): Boolean = {
    val vehicle = vehicles(vehId)
    availableVehicles += vehId -> vehicle
    availableVehiclesIndex.insert(
      new Envelope(new Coordinate(vehicle.spaceTime.loc.getX, vehicle.spaceTime.loc.getY)),
      vehicle
    )
    log.debug("Checked in " + vehId)
    true
  }

  override def makeUnavailable(vehId: Id[BeamVehicle], streetVehicle: StreetVehicle): Option[BeamVehicle] = {
    availableVehicles.get(vehId) match {
      case Some(vehicle) if streetVehicle.locationUTM == vehicle.spaceTime =>
        availableVehicles.remove(vehId)
        val removed = availableVehiclesIndex.remove(
          new Envelope(new Coordinate(vehicle.spaceTime.loc.getX, vehicle.spaceTime.loc.getY)),
          vehicle
        )
        if (!removed) {
          log.error("Didn't find a vehicle in my spatial index, at the location I thought it would be.")
        }
        log.debug("Checked out " + vehId)
        Some(vehicle)
      case _ => None
    }
  }

  override def makeTeleport(vehId: Id[BeamVehicle], whenWhere: SpaceTime): Unit = {
    vehicles(vehId).spaceTime = whenWhere
    log.debug("updated vehicle {} with location {}", vehId, whenWhere)
  }

}
