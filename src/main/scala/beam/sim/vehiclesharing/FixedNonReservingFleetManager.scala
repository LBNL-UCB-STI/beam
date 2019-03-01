package beam.sim.vehiclesharing

import java.util.concurrent.TimeUnit

import akka.actor.Status.Success
import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import beam.agentsim.Resource.{Boarded, NotAvailable, NotifyVehicleIdle, TryToBoardVehicle}
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.agents.household.HouseholdActor.{
  MobilityStatusInquiry,
  MobilityStatusResponse,
  ReleaseVehicle,
  ReleaseVehicleAndReply
}
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.Token
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.ParkingManager.{ParkingInquiry, ParkingInquiryResponse}
import beam.agentsim.scheduler.BeamAgentScheduler.CompletionNotice
import beam.agentsim.scheduler.Trigger.TriggerWithId
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
  val vehicleType: BeamVehicleType
) extends Actor
    with ActorLogging {

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

  override def receive: Receive = {
    case TriggerWithId(InitializeTrigger(_), triggerId) =>
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

    case MobilityStatusInquiry(_, whenWhere, _) =>
      // Search box: 1000 meters around query location
      val boundingBox = new Envelope(new Coordinate(whenWhere.loc.getX, whenWhere.loc.getY))
      boundingBox.expandBy(5000.0)

      val nearbyVehicles = availableVehiclesIndex.query(boundingBox).asScala.toVector.asInstanceOf[Vector[BeamVehicle]]
      nearbyVehicles.sortBy(veh => CoordUtils.calcEuclideanDistance(veh.spaceTime.loc, whenWhere.loc))
      sender ! MobilityStatusResponse(nearbyVehicles.take(5).map { vehicle =>
        Token(vehicle.id, self, vehicle.toStreetVehicle)
      })

    case TryToBoardVehicle(token, who) =>
      availableVehicles.get(token.id) match {
        case Some(vehicle) if token.streetVehicle.locationUTM == vehicle.spaceTime =>
          availableVehicles.remove(token.id)
          val removed = availableVehiclesIndex.remove(
            new Envelope(new Coordinate(vehicle.spaceTime.loc.getX, vehicle.spaceTime.loc.getY)),
            vehicle
          )
          if (!removed) {
            log.error("Didn't find a vehicle in my spatial index, at the location I thought it would be.")
          }
          who ! Boarded(vehicle)
          log.debug("Checked out " + token.id)
        case _ =>
          who ! NotAvailable
      }

    case NotifyVehicleIdle(vId, whenWhere, _, _, _) =>
      val vehId = vId.asInstanceOf[Id[BeamVehicle]]
      vehicles(vehId).spaceTime = whenWhere
      log.debug("updated vehicle {} with location {}", vehId, whenWhere)

    case ReleaseVehicle(vehicle) =>
      availableVehicles += vehicle.id -> vehicle
      availableVehiclesIndex.insert(
        new Envelope(new Coordinate(vehicle.spaceTime.loc.getX, vehicle.spaceTime.loc.getY)),
        vehicle
      )
      log.debug("Checked in " + vehicle.id)

    case ReleaseVehicleAndReply(vehicle, _) =>
      availableVehicles += vehicle.id -> vehicle
      availableVehiclesIndex.insert(
        new Envelope(new Coordinate(vehicle.spaceTime.loc.getX, vehicle.spaceTime.loc.getY)),
        vehicle
      )
      log.debug("Checked in " + vehicle.id)
      sender() ! Success
  }

  def parkingInquiry(whenWhere: SpaceTime) = ParkingInquiry(
    whenWhere.loc,
    whenWhere.loc,
    "wherever",
    AttributesOfIndividual.EMPTY,
    None,
    0,
    0
  )

}
