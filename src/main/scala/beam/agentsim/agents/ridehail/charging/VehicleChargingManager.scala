package beam.agentsim.agents.ridehail.charging

import beam.agentsim.agents.ridehail.RideHailManagerHelper.RideHailAgentLocation
import beam.agentsim.agents.ridehail.charging.VehicleChargingManager.VehicleChargingManagerResult
import beam.agentsim.agents.ridehail.kpis.RealTimeKpis
import beam.agentsim.agents.ridehail.{RideHailDepotParkingManager, RideHailManager}
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.infrastructure.ParkingStall
import beam.sim.BeamServices
import org.matsim.api.core.v01.Id

import scala.collection.mutable

object VehicleChargingManager {

  case class VehicleChargingManagerResult(
    sendToCharge: Vector[(Id[BeamVehicle], ParkingStall)]
  )

  /**
    * Instantiate [[VehicleChargingManager]] following to beam.agentsim.agents.rideHail.charging.vehicleChargingManager.name.
    *
    * @param beamServices Beam services
    * @param resources Map from Id[BeamVehicle] to BeamVehicle with all vehicles controlled by the ride hail manager.
    * @param rideHailDepotParkingManager Depot parking manager
    * @return Instantiated VehicleChargingManager
    */
  def apply(
    beamServices: BeamServices,
    resources: mutable.Map[Id[BeamVehicle], BeamVehicle],
    rideHailDepotParkingManager: RideHailDepotParkingManager[_],
    realTimeKpis: RealTimeKpis
  ): VehicleChargingManager = {

    val vehicleChargingManagerName =
      beamServices.beamConfig.beam.agentsim.agents.rideHail.charging.vehicleChargingManager.name

    val chargingManagerTry = beamServices.beamCustomizationAPI.getChargingManagerFactory.create(
      beamServices,
      resources,
      rideHailDepotParkingManager,
      realTimeKpis,
      vehicleChargingManagerName
    )

    val vehicleChargingManager = chargingManagerTry.recoverWith {
      case exception: Exception =>
        throw new IllegalStateException(s"There is no implementation for `$vehicleChargingManagerName`", exception)
    }.get

    vehicleChargingManager
  }
}

/**
  * Interface to specify VehicleChargingManagers.
  *
  * @param resources Map from Id[BeamVehicle] to BeamVehicle with all vehicles controlled by the ride hail manager.
  */
abstract class VehicleChargingManager(
  val beamServices: BeamServices,
  val resources: mutable.Map[Id[BeamVehicle], BeamVehicle]
) {

  /**
    * queuePriority can be used by any implementing class to manage how vehicles are prioritized. See getQueuePriority
    * for how this behaves.
    */
  protected val queuePriority: mutable.Map[Id[BeamVehicle], Double] = mutable.Map.empty[Id[BeamVehicle], Double]

  /**
    * If any vehicles are added to queuePriority, then whatever value they hold will be used for ordering, with largest
    * values meaning the vehicle has higher priority. If a vehicle is not found in the queuePriority map (but others are present)
    * then this vehicle is given lowest priority (-Infinity). This allows a charge dispatch algorithm to be very selective
    * about which vehicles will move to the head of the priority queue.
    *
    * If the implementing class does not add any data to queuePriority, then default behavior will be used where remaining
    * range is the ordering of the queue. The negative of the range is used so that vehicles with the lowest range
    * are given highest priority.
    *
    * @param beamVehicle
    * @return
    */
  def getQueuePriorityFor(beamVehicle: BeamVehicle) = {
    queuePriority.get(beamVehicle.id) match {
      case Some(priority) =>
        priority
      case None if queuePriority.isEmpty =>
        -beamVehicle.getTotalRemainingRange
      case None =>
        Double.NegativeInfinity
    }
  }

  /**
    * Determine which idle vehicles should be sent to charge and at which charging station.
    *
    * This Function called by [[RideHailManager]] once every beam.agentsim.agents.rideHail.repositioningManager.timeout
    * seconds by the ride hail manager.
    *
    * @param tick Current time
    * @param idleVehicles State information on idle vehicles.
    * @return Tuples representing that vehicle with a given ID should be sent to charge in a given parking stall.
    */
  def findStationsForVehiclesInNeedOfCharging(
    tick: Int,
    idleVehicles: scala.collection.Map[Id[BeamVehicle], RideHailAgentLocation]
  ): VehicleChargingManagerResult

  /** Return the [[BeamVehicle]] corresponding to a given vehicle ID. */
  def findBeamVehicleUsing(vehicleId: Id[BeamVehicle]): Option[BeamVehicle] = {
    resources.get(vehicleId)
  }

  /** Compute the state of charge (from 0 to 1) of a vehicle's battery. */
  def computeStateOfCharge(rideHailAgentLocation: RideHailAgentLocation): Double = {
    val beamVehicle = findBeamVehicleUsing(rideHailAgentLocation.vehicleId).get
    beamVehicle.getState.primaryFuelLevel / rideHailAgentLocation.vehicleType.primaryFuelCapacityInJoule
  }

  /** Get the primary fuel level of the ride hail vehicle */
  def getPrimaryFuelLevelInJ(rideHailAgentLocation: RideHailAgentLocation): Double = {
    val beamVehicle = findBeamVehicleUsing(rideHailAgentLocation.vehicleId).get
    beamVehicle.getState.primaryFuelLevel
  }

  /** Get the remaining range of the ride hail vehicle */
  def getRemainingRangeInM(rideHailAgentLocation: RideHailAgentLocation): Double = {
    val beamVehicle = findBeamVehicleUsing(rideHailAgentLocation.vehicleId).get
    beamVehicle.getTotalRemainingRange
  }

  /**
    * Perform finalization tasks right before the [[RideHailManager]] finishes and the iteration ends.
    *
    * This method can be overriden optionally. By default, no finalization tasks are performed.
    *
    * @param iterationNumber Iteration number
    */
  def finishIteration(iterationNumber: Int): Unit = {}
}
