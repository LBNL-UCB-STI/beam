package beam.agentsim.infrastructure

import akka.actor.ActorRef
import beam.agentsim.agents.vehicles.{BeamVehicle, VehicleManager}
import beam.agentsim.infrastructure.ChargingNetworkManager.ChargingZone
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.core.utils.collections.QuadTree

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/**
  * Created by haitamlaarabi
  */

class ChargingNetwork(managerId: Id[VehicleManager], chargingStationsQTree: QuadTree[ChargingZone])
    extends LazyLogging {
  import ChargingNetwork._

  private val chargingStationMap: Map[ChargingZone, ChargingStation] =
    chargingStationsQTree.values().asScala.map(z => z -> ChargingStation(z)).toMap

  /**
    * lookup a station from a parking stall
    * @param stall the parking stall
    * @return charging station
    */
  def lookupStation(stall: ParkingStall): Option[ChargingStation] =
    chargingStationMap.get(ChargingZone.to(stall))

  /**
    * lookup information about charging vehicle
    * @param vehicleId vehicle Id
    * @return charging vehicle
    */
  def lookupVehicle(vehicleId: Id[BeamVehicle]): Option[ChargingVehicle] =
    lookupVehicles.get(vehicleId)

  /**
    * get all stations
    * @return list of station
    */
  def lookupStations: List[ChargingStation] =
    chargingStationMap.values.toList

  /**
    * get connected vehicles
    * @return list of charging vehicle
    */
  def lookupConnectedVehicles: Map[Id[BeamVehicle], ChargingVehicle] =
    chargingStationMap.flatMap(_._2.connectedVehicles)

  /**
    * get all vehicles
    * @return
    */
  def lookupVehicles: Map[Id[BeamVehicle], ChargingVehicle] =
    chargingStationMap.flatMap(_._2.vehicles)

  /**
    * get name of the vehicle manager
    * @return VehicleManager
    */
  def vehicleManagerId: Id[VehicleManager] = managerId

  /**
    * clear charging vehicle map
    */
  def clear(): Unit = chargingStationMap.foreach(_._2.clear())

  /**
    * Connect to charging point or add to waiting line
    * @param tick current time
    * @param vehicle vehicle to charge
    * @return a tuple of the status of the charging vehicle and the connection status
    */
  def connectVehicle(tick: Int, vehicle: BeamVehicle, theSender: ActorRef): ChargingVehicle = {
    if (vehicle.stall.isEmpty) {
      throw new RuntimeException(s"Vehicle $vehicle doesn't have a stall!")
    } else {
      val stall = vehicle.stall.get
      lookupStation(stall).map(station => station.connect(tick, vehicle, stall, theSender)).get
    }
  }

  /**
    * Disconnect the vehicle for the charging point/station
    * @param vehicle vehicle to disconnect
    * @return a tuple of the status of the charging vehicle and the connection status
    */
  def disconnectVehicle(vehicle: BeamVehicle): Boolean = {
    if (vehicle.stall.isEmpty) {
      throw new RuntimeException(s"Vehicle $vehicle doesn't have a stall!")
    } else {
      val stall = vehicle.stall.get
      lookupStation(stall).flatMap(station => station.disconnect(vehicle.id)).isDefined
    }
  }

  /**
    * transfer vehciles from waiting line to connected
    * @param station the corresponding station
    * @return list of vehicle that connected
    */
  def processWaitingLine(station: ChargingStation): List[ChargingVehicle] = station.connectFromWaitingLine()
}

object ChargingNetwork {

  object ConnectionStatus extends Enumeration {
    type ConnectionStatus = Value
    val Waiting, Connected, Disconnected, NotConnected = Value
  }

  final case class ChargingStation(zone: ChargingZone) {
    import ConnectionStatus._
    private val connectedVehiclesInternal = TrieMap.empty[Id[BeamVehicle], ChargingVehicle]
    private val waitingLineInternal: mutable.PriorityQueue[ChargingVehicle] =
      mutable.PriorityQueue.empty[ChargingVehicle](Ordering.by((_: ChargingVehicle).arrivalTime).reverse)

    def numAvailableChargers: Int = zone.numChargers - connectedVehiclesInternal.size

    def connectedVehicles: scala.collection.Map[Id[BeamVehicle], ChargingVehicle] =
      connectedVehiclesInternal.readOnlySnapshot()

    def waitingLineVehicles: scala.collection.Map[Id[BeamVehicle], ChargingVehicle] =
      waitingLineInternal.map(x => x.vehicle.id -> x).toMap

    def vehicles: scala.collection.Map[Id[BeamVehicle], ChargingVehicle] = connectedVehicles ++ waitingLineVehicles

    /**
      * add vehicle to connected list and connect to charging point
      * @param tick current time
      * @param vehicle vehicle to connect
      * @return status of connection
      */
    private[ChargingNetwork] def connect(
      tick: Int,
      vehicle: BeamVehicle,
      stall: ParkingStall,
      theSender: ActorRef
    ): ChargingVehicle = this.synchronized {
      val chargingVehicle = ChargingVehicle(vehicle, stall, this, tick, theSender)
      if (numAvailableChargers > 0) {
        chargingVehicle.updateStatus(Connected)
        connectedVehiclesInternal.put(vehicle.id, chargingVehicle)
      } else {
        chargingVehicle.updateStatus(Waiting)
        waitingLineInternal.enqueue(chargingVehicle)
      }
      chargingVehicle
    }

    /**
      * remove vehicle from connected list and disconnect from charging point
      * @param vehicleId vehicle to disconnect
      * @return status of connection
      */
    private[ChargingNetwork] def disconnect(vehicleId: Id[BeamVehicle]): Option[ChargingVehicle] =
      this.synchronized {
        connectedVehiclesInternal.remove(vehicleId).map { v =>
          v.updateStatus(Disconnected)
          v
        }
      }

    /**
      * process waiting line by removing vehicle from waiting line and adding it to the connected list
      * @return map of vehicles that got connected
      */
    private[ChargingNetwork] def connectFromWaitingLine(): List[ChargingVehicle] = this.synchronized {
      (1 to Math.min(waitingLineInternal.size, numAvailableChargers)).map { _ =>
        val v = waitingLineInternal.dequeue()
        v.updateStatus(Connected)
        connectedVehiclesInternal.put(v.vehicle.id, v)
        v
      }.toList
    }

    private[ChargingNetwork] def clear(): Unit = this.synchronized {
      connectedVehiclesInternal.clear()
      waitingLineInternal.clear()
    }
  }

  final case class ChargingCycle(startTime: Int, energy: Double, duration: Int)

  final case class ChargingVehicle(
    vehicle: BeamVehicle,
    stall: ParkingStall,
    chargingStation: ChargingStation,
    arrivalTime: Int,
    theSender: ActorRef
  ) extends LazyLogging {
    import ConnectionStatus._
    private val chargingSessionInternal = ListBuffer.empty[ChargingCycle]
    private val statusInternal = ListBuffer.empty[ConnectionStatus]

    private[ChargingNetwork] def updateStatus(status: ConnectionStatus): Unit = this.synchronized {
      statusInternal.append(status)
    }

    /**
      * adding a new charging cycle to the charging session
      * @param startTime start time of the charging cycle
      * @param energy energy delivered
      * @param duration duration of charging
      * @return boolean value expressing if the charging cycle has been added
      */
    def processChargingCycle(startTime: Int, energy: Double, duration: Int): Option[ChargingCycle] =
      this.synchronized {
        chargingSessionInternal.lastOption match {
          case Some(cycle: ChargingCycle) if cycle.startTime == startTime && cycle.duration > duration =>
            chargingSessionInternal.remove(chargingSessionInternal.size - 1)
            val cycle = ChargingCycle(startTime, energy, duration)
            chargingSessionInternal.append(cycle)
            Some(cycle)
          case Some(cycle: ChargingCycle) if cycle.startTime == startTime =>
            // keep existing charging cycle
            logger.debug(s"the new charging cycle of vehicle $vehicle ends after the end of the last cycle")
            None
          case Some(cycle: ChargingCycle) if cycle.startTime > startTime =>
            // This should never happen
            logger.error(s"the new charging cycle of vehicle $vehicle starts before the start of the last cycle")
            None
          case _ =>
            val cycle = ChargingCycle(startTime, energy, duration)
            chargingSessionInternal.append(cycle)
            Some(cycle)
        }
      }

    def computeSessionStartTime: Int = this.synchronized {
      chargingSessionInternal.headOption.map(_.startTime).getOrElse(arrivalTime)
    }
    def computeSessionEnergy: Double = this.synchronized { chargingSessionInternal.map(_.energy).sum }
    def computeSessionDuration: Long = this.synchronized { chargingSessionInternal.map(_.duration).sum }
    def computeSessionEndTime: Int = (computeSessionStartTime + computeSessionDuration).toInt
    def latestChargingCycle: Option[ChargingCycle] = this.synchronized { chargingSessionInternal.lastOption }
    def status: ConnectionStatus = this.synchronized { statusInternal.last }
  }
}
