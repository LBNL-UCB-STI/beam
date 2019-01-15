package beam.sim.vehiclesharing

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import beam.agentsim.Resource.{Boarded, NotAvailable, TryToBoardVehicle}
import beam.agentsim.agents.household.HouseholdActor.{MobilityStatusInquiry, MobilityStatusResponse, ReleaseVehicle}
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.Token
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.ParkingManager.{ParkingInquiry, ParkingInquiryResponse}
import beam.agentsim.infrastructure.ParkingStall.NoNeed
import beam.sim.population.AttributesOfIndividual
import com.vividsolutions.jts.geom.{Coordinate, Envelope}
import com.vividsolutions.jts.index.quadtree.Quadtree
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.ExecutionContext

private[vehiclesharing] class FixedNonReservingFleetManager(
  val parkingManager: ActorRef,
  val locations: Iterable[Coord]
) extends Actor
    with ActorLogging {

  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)
  private implicit val executionContext: ExecutionContext = context.dispatcher

  private val vehicles = locations.zipWithIndex map {
    case (location, ix) =>
      val vehicle = new BeamVehicle(
        Id.createVehicleId("fixed-non-reserving-vehicle-fleet-" + ix),
        new Powertrain(0.0),
        None,
        BeamVehicleType.defaultCarBeamVehicleType,
        None
      )
      vehicle.manager = Some(self)
      vehicle.spaceTime = SpaceTime(location, 0)
      vehicle
  }

  // Pipe my vehicles through the parking agent.
  // They will become available once they are parked.
  for (veh <- vehicles) {
    veh.manager = Some(self)
    parkingManager ? parkingInquiry(veh.spaceTime) collect {
      case ParkingInquiryResponse(stall, _) =>
        veh.useParkingStall(stall)
        ReleaseVehicle(veh)
    } pipeTo self
  }

  private val availableVehicles = mutable.Map[Id[BeamVehicle], BeamVehicle]()
  private val availableVehiclesIndex = new Quadtree

  override def receive: Receive = {

    case MobilityStatusInquiry(whenWhere) =>
      // Search box: 500 meters around query location
      val boundingBox = new Envelope(new Coordinate(whenWhere.loc.getX, whenWhere.loc.getY))
      boundingBox.expandBy(500.0)

      val nearbyVehicles = availableVehiclesIndex.query(boundingBox).asScala.toVector.asInstanceOf[Vector[BeamVehicle]]
      sender ! MobilityStatusResponse(nearbyVehicles.take(5).map { vehicle =>
        Token(vehicle.id, self, vehicle.toStreetVehicle)
      })

    case TryToBoardVehicle(vehicleId, who) =>
      availableVehicles.get(vehicleId) match {
        case Some(vehicle) =>
          availableVehicles.remove(vehicleId)
          val removed = availableVehiclesIndex.remove(
            new Envelope(new Coordinate(vehicle.spaceTime.loc.getX, vehicle.spaceTime.loc.getY)),
            vehicle
          )
          if (!removed) {
            log.error("Didn't find a vehicle in my spatial index, at the location I thought it would be.")
          }
          who ! Boarded(vehicle)
          log.debug("Checked out " + vehicleId)
        case None =>
          who ! NotAvailable
      }

    case ReleaseVehicle(vehicle) =>
      availableVehicles += vehicle.id -> vehicle
      availableVehiclesIndex.insert(
        new Envelope(new Coordinate(vehicle.spaceTime.loc.getX, vehicle.spaceTime.loc.getY)),
        vehicle
      )
      log.debug("Checked in " + vehicle.id)

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

}
