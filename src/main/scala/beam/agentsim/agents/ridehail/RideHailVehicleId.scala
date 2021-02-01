package beam.agentsim.agents.ridehail

import beam.agentsim.agents.vehicles.BeamVehicle
import org.matsim.api.core.v01.Id

object RideHailVehicleId {
  private val VEHICLE_ID_PREFIX = f"rideHailVehicle-"
  private val FLEET_SEPARATOR = "@"

  /**
    * Create a [[RideHailVehicleId]] from an [[Id[BeamVehicle]]].
    *
    * @param vehicleId Ride-hail vehicle ID.
    * @return Corresponding [[RideHailVehicleId]]
    */
  def apply(vehicleId: Id[BeamVehicle]): RideHailVehicleId = {
    require(isRideHail(vehicleId))

    val vehicleIdStr = vehicleId.toString
    val idWithFleetId = vehicleIdStr.stripPrefix(VEHICLE_ID_PREFIX)

    idWithFleetId.split(FLEET_SEPARATOR) match {
      case Array(id, fleetId) =>
        new RideHailVehicleId(id, fleetId)
      case _ =>
        throw new Exception(f"Invalid idWithFleet $idWithFleetId")
    }
  }

  /**  Returns true if an [[Id[BeamVehicle]]] represents a ride-hail vehicle ID. */
  def isRideHail(vehicleId: Id[BeamVehicle]): Boolean = {
    vehicleId.toString.startsWith(VEHICLE_ID_PREFIX)
  }
}

/**
  * RideHailVehicleId is a wrapper around [[Id[BeamVehicle]]] to better identify ride-hail vehicles.
  *
  * The beamVehicleId (e.g., rideHailVehicle-1@myFleet) is comprised of three parts:
  * 1. Prefix: "rideHailVehicle-" which indicates that this is a ride-hail vehicle.
  * 2. ID: "1" which identifies the vehicle.
  * 3. Fleet ID: "myFleet" which identifies the fleet to which the vehicle belongs.
  *
  * It is possible to losslessly convert from [[RideHailVehicleId]] to [[Id[BeamVehicle]]] so that one can use
  * whichever one is suited to a particular context.
  *
  * This class is needed because objects like [[beam.agentsim.events.PathTraversalEvent]] take
  * [[Id[BeamVehicle]]]. However, at the time of processing the event, we often need the extra information provided
  * by this class (i.e., if the ID corresponds to a ride-hail vehicle and, if so, the vehicle's fleet).
  *
  * @param id Ride-hail vehicle ID (without prefix).
  * @param fleetId ID of the fleet to which the vehicle belongs.
  */
case class RideHailVehicleId(id: String, fleetId: String) extends Ordered[RideHailVehicleId] {

  val beamVehicleId: Id[BeamVehicle] = Id.create(
    f"${RideHailVehicleId.VEHICLE_ID_PREFIX}${id}${RideHailVehicleId.FLEET_SEPARATOR}${fleetId}",
    classOf[BeamVehicle]
  )

  override def toString: String = {
    beamVehicleId.toString
  }

  def compare(that: RideHailVehicleId): Int =
    this.id compare that.id
}
