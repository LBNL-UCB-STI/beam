package beam.agentsim.agents.ridehail

import akka.actor.ActorRef
import beam.agentsim.agents.ridehail.RideHailVehicleManager._
import beam.agentsim.agents.vehicles.BeamVehicle.BeamVehicleState
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.Location
import beam.router.Modes.BeamMode.CAR
import beam.sim.Geofence
import beam.sim.common.GeoUtils
import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree
import org.matsim.core.utils.geometry.CoordUtils
import org.matsim.vehicles.Vehicle

import scala.collection.JavaConverters._
import scala.collection.mutable

object RideHailAgentETAComparatorMinTimeToCustomer extends Ordering[RideHailAgentETA] {
  override def compare(
    o1: RideHailAgentETA,
    o2: RideHailAgentETA
  ): Int = {
    java.lang.Double.compare(o1.timeToCustomer, o2.timeToCustomer)
  }
}

/**
  * BEAM
  */
class RideHailVehicleManager(val rideHailManager: RideHailManager, boundingBox: Envelope) extends LazyLogging {

  val vehicleState: mutable.Map[Id[Vehicle], BeamVehicleState] =
    mutable.Map[Id[Vehicle], BeamVehicleState]()

  val availableRideHailAgentSpatialIndex = {
    new QuadTree[RideHailAgentLocation](
      boundingBox.getMinX,
      boundingBox.getMinY,
      boundingBox.getMaxX,
      boundingBox.getMaxY
    )
  }

  val inServiceRideHailAgentSpatialIndex = {
    new QuadTree[RideHailAgentLocation](
      boundingBox.getMinX,
      boundingBox.getMinY,
      boundingBox.getMaxX,
      boundingBox.getMaxY
    )
  }

  val outOfServiceRideHailAgentSpatialIndex = {
    new QuadTree[RideHailAgentLocation](
      boundingBox.getMinX,
      boundingBox.getMinY,
      boundingBox.getMaxX,
      boundingBox.getMaxY
    )
  }
  val availableRideHailVehicles = mutable.HashMap[Id[Vehicle], RideHailAgentLocation]()
  val outOfServiceRideHailVehicles = mutable.HashMap[Id[Vehicle], RideHailAgentLocation]()
  val inServiceRideHailVehicles = mutable.HashMap[Id[Vehicle], RideHailAgentLocation]()

  def getVehicleState(vehicleId: Id[Vehicle]): BeamVehicleState =
    vehicleState(vehicleId)

  def getIdleVehiclesWithinRadius(
    pickupLocation: Location,
    radius: Double
  ): Iterable[(RideHailAgentLocation, Double)] = {
    val nearbyRideHailAgents = availableRideHailAgentSpatialIndex
      .getDisk(pickupLocation.getX, pickupLocation.getY, radius)
      .asScala
      .view
    val distances2RideHailAgents =
      nearbyRideHailAgents.map(rideHailAgentLocation => {
        val distance = CoordUtils
          .calcProjectedEuclideanDistance(pickupLocation, rideHailAgentLocation.currentLocationUTM.loc)
        (rideHailAgentLocation, distance)
      })
    distances2RideHailAgents.filter(x => availableRideHailVehicles.contains(x._1.vehicleId))
  }

  def getRideHailAgentLocation(vehicleId: Id[Vehicle]): RideHailAgentLocation = {
    getServiceStatusOf(vehicleId) match {
      case Available =>
        availableRideHailVehicles(vehicleId)
      case InService =>
        inServiceRideHailVehicles(vehicleId)
      case OutOfService =>
        outOfServiceRideHailVehicles(vehicleId)
    }
  }

  def getClosestIdleVehiclesWithinRadiusByETA(
    pickupLocation: Coord,
    dropoffLocation: Coord,
    radius: Double,
    customerRequestTime: Long,
    excludeRideHailVehicles: Set[Id[Vehicle]] = Set(),
    secondsPerEuclideanMeterFactor: Double = 0.1 // (~13.4m/s)^-1 * 1.4
  ): Option[RideHailAgentETA] = {
    var start = System.currentTimeMillis()
    val nearbyAvailableRideHailAgents = availableRideHailAgentSpatialIndex
      .getDisk(pickupLocation.getX, pickupLocation.getY, radius)
      .asScala
      .view
      .filter { x =>
        availableRideHailVehicles.contains(x.vehicleId) && !excludeRideHailVehicles.contains(x.vehicleId) &&
        (x.geofence.isEmpty || (GeoUtils.distFormula(
          pickupLocation,
          new Coord(x.geofence.get.geofenceX, x.geofence.get.geofenceY)
        ) <= x.geofence.get.geofenceRadius && GeoUtils.distFormula(
          dropoffLocation,
          new Coord(x.geofence.get.geofenceX, x.geofence.get.geofenceY)
        ) <= x.geofence.get.geofenceRadius))
      }

    var end = System.currentTimeMillis()
    val diff1 = end - start

    start = System.currentTimeMillis()
    val times2RideHailAgents = nearbyAvailableRideHailAgents
      .map { rideHailAgentLocation =>
        val distance =
          CoordUtils.calcProjectedEuclideanDistance(pickupLocation, rideHailAgentLocation.currentLocationUTM.loc)
        // we consider the time to travel to the customer and the time before the vehicle is actually ready (due to
        // already moving or dropping off a customer, etc.)
        val extra = Math.max(rideHailAgentLocation.currentLocationUTM.time - customerRequestTime, 0)
        val timeToCustomer = distance * secondsPerEuclideanMeterFactor + extra
        RideHailAgentETA(rideHailAgentLocation, distance, timeToCustomer)
      }
    end = System.currentTimeMillis()
    val diff2 = end - start

    logger.whenDebugEnabled {
      val sortedByTime = times2RideHailAgents.toVector.sortBy(x => x.timeToCustomer)
      logger.info(s"At tick $customerRequestTime there were AvailableRideHailAgents: $sortedByTime")
    }

    if (diff1 + diff2 > 100)
      logger.debug(
        s"getClosestIdleVehiclesWithinRadiusByETA for $pickupLocation with $radius nearbyAvailableRideHailAgents: $diff1, diff2: $diff2. Total: ${diff1 + diff2} ms"
      )
    if (times2RideHailAgents.isEmpty) None
    else {
      Some(times2RideHailAgents.min(RideHailAgentETAComparatorMinTimeToCustomer))
    }
  }

  def getClosestIdleVehiclesWithinRadius(
    pickupLocation: Coord,
    radius: Double
  ): Array[RideHailAgentLocation] = {
    val idleVehicles = getIdleVehiclesWithinRadius(pickupLocation, radius).toArray
    java.util.Arrays.sort(idleVehicles, RideHailAgentLocationWithRadiusOrdering)
    idleVehicles.map { case (location, _) => location }
  }

  def getIdleVehicles: mutable.HashMap[Id[Vehicle], RideHailAgentLocation] = {
    availableRideHailVehicles
  }

  def getServiceStatusOf(vehicleId: Id[Vehicle]): RideHailVehicleManager.RideHailServiceStatus = {
    if (availableRideHailVehicles.contains(vehicleId)) {
      Available
    } else if (inServiceRideHailVehicles.contains(vehicleId)) {
      InService
    } else if (outOfServiceRideHailVehicles.contains(vehicleId)) {
      OutOfService
    } else {
      logger.error(s"Vehicle {} does not have a service status, assuming out of service", vehicleId)
      OutOfService
    }
  }

  def updateLocationOfAgent(
    vehicleId: Id[Vehicle],
    whenWhere: SpaceTime,
    serviceStatus: RideHailVehicleManager.RideHailServiceStatus
  ) = {
    serviceStatus match {
      case Available =>
        availableRideHailVehicles.get(vehicleId) match {
          case Some(prevLocation) =>
            val newLocation = prevLocation.copy(currentLocationUTM = whenWhere)
            availableRideHailAgentSpatialIndex.remove(
              prevLocation.currentLocationUTM.loc.getX,
              prevLocation.currentLocationUTM.loc.getY,
              prevLocation
            )
            availableRideHailAgentSpatialIndex.put(
              newLocation.currentLocationUTM.loc.getX,
              newLocation.currentLocationUTM.loc.getY,
              newLocation
            )
            availableRideHailVehicles.put(newLocation.vehicleId, newLocation)
          case None =>
        }
      case InService =>
        inServiceRideHailVehicles.get(vehicleId) match {
          case Some(prevLocation) =>
            val newLocation = prevLocation.copy(currentLocationUTM = whenWhere)
            inServiceRideHailAgentSpatialIndex.remove(
              prevLocation.currentLocationUTM.loc.getX,
              prevLocation.currentLocationUTM.loc.getY,
              prevLocation
            )
            inServiceRideHailAgentSpatialIndex.put(
              newLocation.currentLocationUTM.loc.getX,
              newLocation.currentLocationUTM.loc.getY,
              newLocation
            )
            inServiceRideHailVehicles.put(newLocation.vehicleId, newLocation)
          case None =>
        }
      case OutOfService =>
        outOfServiceRideHailVehicles.get(vehicleId) match {
          case Some(prevLocation) =>
            val newLocation = prevLocation.copy(currentLocationUTM = whenWhere)
            outOfServiceRideHailAgentSpatialIndex.remove(
              prevLocation.currentLocationUTM.loc.getX,
              prevLocation.currentLocationUTM.loc.getY,
              prevLocation
            )
            outOfServiceRideHailAgentSpatialIndex.put(
              newLocation.currentLocationUTM.loc.getX,
              newLocation.currentLocationUTM.loc.getY,
              newLocation
            )
            outOfServiceRideHailVehicles.put(newLocation.vehicleId, newLocation)
          case None =>
        }
    }
  }

  def makeAvailable(agentLocation: RideHailAgentLocation) = {
    availableRideHailVehicles.put(agentLocation.vehicleId, agentLocation)
    availableRideHailAgentSpatialIndex.put(
      agentLocation.currentLocationUTM.loc.getX,
      agentLocation.currentLocationUTM.loc.getY,
      agentLocation
    )
    inServiceRideHailVehicles.remove(agentLocation.vehicleId)
    inServiceRideHailAgentSpatialIndex.remove(
      agentLocation.currentLocationUTM.loc.getX,
      agentLocation.currentLocationUTM.loc.getY,
      agentLocation
    )
    outOfServiceRideHailVehicles.remove(agentLocation.vehicleId)
    outOfServiceRideHailAgentSpatialIndex.remove(
      agentLocation.currentLocationUTM.loc.getX,
      agentLocation.currentLocationUTM.loc.getY,
      agentLocation
    )
  }

  def putIntoService(agentLocation: RideHailAgentLocation) = {
    availableRideHailVehicles.remove(agentLocation.vehicleId)
    availableRideHailAgentSpatialIndex.remove(
      agentLocation.currentLocationUTM.loc.getX,
      agentLocation.currentLocationUTM.loc.getY,
      agentLocation
    )
    outOfServiceRideHailVehicles.remove(agentLocation.vehicleId)
    outOfServiceRideHailAgentSpatialIndex.remove(
      agentLocation.currentLocationUTM.loc.getX,
      agentLocation.currentLocationUTM.loc.getY,
      agentLocation
    )
    inServiceRideHailVehicles.put(agentLocation.vehicleId, agentLocation)
    inServiceRideHailAgentSpatialIndex.put(
      agentLocation.currentLocationUTM.loc.getX,
      agentLocation.currentLocationUTM.loc.getY,
      agentLocation
    )
  }

  def putOutOfService(agentLocation: RideHailAgentLocation) = {
    availableRideHailVehicles.remove(agentLocation.vehicleId)
    availableRideHailAgentSpatialIndex.remove(
      agentLocation.currentLocationUTM.loc.getX,
      agentLocation.currentLocationUTM.loc.getY,
      agentLocation
    )
    inServiceRideHailVehicles.remove(agentLocation.vehicleId)
    inServiceRideHailAgentSpatialIndex.remove(
      agentLocation.currentLocationUTM.loc.getX,
      agentLocation.currentLocationUTM.loc.getY,
      agentLocation
    )
    outOfServiceRideHailVehicles.put(agentLocation.vehicleId, agentLocation)
    outOfServiceRideHailAgentSpatialIndex.put(
      agentLocation.currentLocationUTM.loc.getX,
      agentLocation.currentLocationUTM.loc.getY,
      agentLocation
    )
  }
}

object RideHailVehicleManager {
  case class RideHailAgentLocation(
    rideHailAgent: ActorRef,
    vehicleId: Id[Vehicle],
    vehicleTypeId: Id[BeamVehicleType],
    currentLocationUTM: SpaceTime,
    geofence: Option[Geofence] = None
  ) {

    def toStreetVehicle: StreetVehicle = {
      StreetVehicle(vehicleId, vehicleTypeId, currentLocationUTM, CAR, asDriver = true)
    }
  }

  case class RideHailAgentETA(
    agentLocation: RideHailAgentLocation,
    distance: Double,
    timeToCustomer: Double
  )
  sealed trait RideHailServiceStatus
  /* Available means vehicle can be assigned to a new customer */
  case object Available extends RideHailServiceStatus
  case object InService extends RideHailServiceStatus
  case object OutOfService extends RideHailServiceStatus
}
