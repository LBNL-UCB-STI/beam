package beam.sim.vehiclesharing

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import beam.agentsim.Resource.{Boarded, NotAvailable, TryToBoardVehicle}
import beam.agentsim.agents.household.HouseholdActor.{MobilityStatusInquiry, MobilityStatusResponse, ReleaseVehicle}
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.ParkingManager.{ParkingInquiry, ParkingInquiryResponse}
import beam.agentsim.infrastructure.ParkingStall.NoNeed
import com.vividsolutions.jts.geom.{Coordinate, Envelope}
import com.vividsolutions.jts.index.quadtree.Quadtree
import org.matsim.api.core.v01.Id

import collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.ExecutionContext

class FixedNonReservingVehicleFleet(val parkingManager: ActorRef, val vehicles: Seq[BeamVehicle])
    extends Actor
    with ActorLogging {

  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)
  private implicit val executionContext: ExecutionContext = context.dispatcher

  // Pipe my vehicles through the parking agent.
  // They will become available once they are parked.
  for (veh <- vehicles) {
    veh.manager = Some(self)
    parkingManager ? parkingInquiry(veh.spaceTime) collect {
      case ParkingInquiryResponse(stall, _) =>
        veh.useParkingStall(stall)
        veh.exclusiveAccess = false
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
      sender ! MobilityStatusResponse(nearbyVehicles)

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
          who ! Boarded
        case None =>
          who ! NotAvailable
      }

    case ReleaseVehicle(vehicle) =>
      vehicle.exclusiveAccess = false
      availableVehicles += vehicle.id -> vehicle
      availableVehiclesIndex.insert(
        new Envelope(new Coordinate(vehicle.spaceTime.loc.getX, vehicle.spaceTime.loc.getY)),
        vehicle
      )

  }

  def parkingInquiry(whenWhere: SpaceTime) = ParkingInquiry(
    whenWhere.loc,
    whenWhere.loc,
    "wherever",
    0,
    NoNeed,
    0,
    0
  )

}
