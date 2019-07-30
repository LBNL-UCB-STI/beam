package beam.sim

import beam.analysis.RideHailHistoricalData
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Activity
import org.matsim.vehicles.Vehicle

class RideHailState extends LazyLogging {

  @volatile
  private var _vehicleToActivityMap: Map[Id[Vehicle], Activity] = Map.empty

  def setVehicleToActivityMap(vehicleToActivityMap: Map[Id[Vehicle], Activity]): Unit = {
    _vehicleToActivityMap = vehicleToActivityMap
  }

  def getVehicleToActivityMap: Map[Id[Vehicle], Activity] = {
    _vehicleToActivityMap
  }

  @volatile
  private var _allRideHailVehicles: Set[Id[Vehicle]] = Set.empty

  def setAllRideHailVehicles(vehicles: Set[Id[Vehicle]]): Unit = {
    _allRideHailVehicles = vehicles
  }

  def getAllRideHailVehicles: Set[Id[Vehicle]] = {
    _allRideHailVehicles
  }

  @volatile
  private var _rideHailUtilization: RideHailHistoricalData =
    RideHailHistoricalData(Set.empty, Set.empty, Set.empty, IndexedSeq.empty)

  def setRideHailUtilization(utilization: RideHailHistoricalData): Unit = {
    logger.info(s"""
         |Set new utilization:
         |notMovedAtAll: ${utilization.notMovedAtAll.size}
         |movedWithoutPassenger: ${utilization.movedWithoutPassenger.size}
         |movedWithPassengers: ${utilization.movedWithPassengers.size}
         |total rides: ${utilization.rides.size}""".stripMargin)
    _rideHailUtilization = utilization
  }

  def getRideHailUtilization: RideHailHistoricalData = {
    _rideHailUtilization
  }
}
