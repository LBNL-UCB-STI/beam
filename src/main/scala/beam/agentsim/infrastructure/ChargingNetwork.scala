package beam.agentsim.infrastructure

import akka.actor.ActorRef
import beam.agentsim.agents.vehicles.{BeamVehicle, VehicleManager}
import beam.agentsim.infrastructure.ChargingNetworkManager.ChargingZone
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.ParkingType
import beam.agentsim.infrastructure.taz.TAZ
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.core.utils.collections.QuadTree

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/**
  * Created by haitamlaarabi
  */

class ChargingNetwork(managerId: Id[VehicleManager], chargingStationsQTree: QuadTree[ChargingZone])
    extends LazyLogging {
  import ChargingNetwork._

  private val chargingStationMap: Map[String, ChargingStation] =
    chargingStationsQTree.values().asScala.map(z => z.id -> ChargingStation(z)).toMap

  val chargingStations: List[ChargingStation] = chargingStationMap.values.toList

  /**
    *
    * @return all vehicles still connected to a charging point
    */
  def connectedVehicles: Map[Id[BeamVehicle], ChargingVehicle] = chargingStationMap.flatMap(_._2.connectedVehicles)

  /**
    *
    * @return all vehicles, connected, and the ones waiting in line
    */
  def vehicles: Map[Id[BeamVehicle], ChargingVehicle] = chargingStationMap.flatMap(_._2.vehicles)

  /**
    * lookup a station from attributes
    * @param tazId the taz id
    * @param parkingType the parking type
    * @param chargingPointType the charging type
    * @return
    */
  def lookupStation(
    tazId: Id[TAZ],
    parkingType: ParkingType,
    chargingPointType: ChargingPointType
  ): Option[ChargingStation] = chargingStationMap.get(s"cs_${managerId}_${tazId}_${parkingType}_${chargingPointType}")

  /**
    * lookup information about charging vehicle
    * @param vehicleId vehicle Id
    * @return charging vehicle
    */
  def lookupVehicle(vehicleId: Id[BeamVehicle]): Option[ChargingVehicle] = vehicles.get(vehicleId)

  /**
    * get name of the vehicle manager
    * @return VehicleManager
    */
  def vehicleManagerId: Id[VehicleManager] = managerId

  /**
    * clear charging vehicle map
    */
  def clearAllMappedStations(): Unit = chargingStationMap.foreach(_._2.clearAllVehiclesFromTheStation())

  /**
    * Connect to charging point or add to waiting line
    * @param tick current time
    * @param vehicle vehicle to charge
    * @return a tuple of the status of the charging vehicle and the connection status
    */
  def attemptToConnectVehicle(tick: Int, vehicle: BeamVehicle, theSender: ActorRef): ChargingVehicle = {
    vehicle.stall match {
      case Some(stall) =>
        lookupStation(stall.tazId, stall.parkingType, stall.chargingPointType.get)
          .map(station => station.connect(tick, vehicle, stall, theSender))
          .get
      case _ => throw new RuntimeException(s"CNM cannot start charging the vehicle $vehicle that doesn't have a stall!")
    }
  }

  /**
    * Disconnect the vehicle for the charging point/station
    * @param chargingVehicle vehicle to disconnect
    * @return a tuple of the status of the charging vehicle and the connection status
    */
  def disconnectVehicle(chargingVehicle: ChargingVehicle): Option[ChargingVehicle] =
    chargingVehicle.chargingStation.disconnect(chargingVehicle.vehicle.id)

  /**
    * transfer vehciles from waiting line to connected
    * @param station the corresponding station
    * @return list of vehicle that connected
    */
  def processWaitingLine(tick: Int, station: ChargingStation): List[ChargingVehicle] =
    station.connectFromWaitingLine(tick)
}

object ChargingNetwork {

  object ConnectionStatus extends Enumeration {
    type ConnectionStatus = Value
    val Waiting, Connected, Disconnected, NotConnected = Value
  }

  final case class ChargingStation(zone: ChargingZone) {
    import ConnectionStatus._
    private val connectedVehiclesInternal = mutable.HashMap.empty[Id[BeamVehicle], ChargingVehicle]
    private val waitingLineInternal: mutable.PriorityQueue[ChargingVehicle] =
      mutable.PriorityQueue.empty[ChargingVehicle](Ordering.by((_: ChargingVehicle).arrivalTime).reverse)

    def numAvailableChargers: Int = zone.numChargers - connectedVehiclesInternal.size
    def connectedVehicles: Map[Id[BeamVehicle], ChargingVehicle] = connectedVehiclesInternal.toMap

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
      if (numAvailableChargers > 0) {
        val chargingVehicle = ChargingVehicle(vehicle, stall, this, tick, tick, theSender)
        chargingVehicle.updateStatus(Connected)
        connectedVehiclesInternal.put(vehicle.id, chargingVehicle)
        chargingVehicle
      } else {
        val chargingVehicle = ChargingVehicle(vehicle, stall, this, tick, -1, theSender)
        chargingVehicle.updateStatus(Waiting)
        waitingLineInternal.enqueue(chargingVehicle)
        chargingVehicle
      }
    }

    /**
      * remove vehicle from connected list and disconnect from charging point
      * @param vehicleId vehicle to disconnect
      * @return status of connection
      */
    private[ChargingNetwork] def disconnect(vehicleId: Id[BeamVehicle]): Option[ChargingVehicle] = this.synchronized {
      connectedVehiclesInternal.remove(vehicleId).map { v =>
        v.updateStatus(Disconnected)
        v
      }
    }

    /**
      * process waiting line by removing vehicle from waiting line and adding it to the connected list
      * @return map of vehicles that got connected
      */
    private[ChargingNetwork] def connectFromWaitingLine(tick: Int): List[ChargingVehicle] = this.synchronized {
      (1 to Math.min(waitingLineInternal.size, numAvailableChargers)).map { _ =>
        val vTemp = waitingLineInternal.dequeue()
        val v = vTemp.copy(sessionStartTime = tick)
        v.updateStatus(Connected)
        connectedVehiclesInternal.put(v.vehicle.id, v)
        v
      }.toList
    }

    private[ChargingNetwork] def clearAllVehiclesFromTheStation(): Unit = {
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
    sessionStartTime: Int,
    theSender: ActorRef
  ) extends LazyLogging {
    import ConnectionStatus._
    private val chargingSessionInternal = ListBuffer.empty[ChargingCycle]
    private val statusInternal = ListBuffer.empty[ConnectionStatus]

    private[ChargingNetwork] def updateStatus(status: ConnectionStatus): Unit = statusInternal.append(status)

    /**
      * adding a new charging cycle to the charging session
      * @param startTime start time of the charging cycle
      * @param energy energy delivered
      * @param duration duration of charging
      * @return boolean value expressing if the charging cycle has been added
      */
    def processChargingCycle(startTime: Int, energy: Double, duration: Int): Option[ChargingCycle] =
      chargingSessionInternal.lastOption match {
        case Some(cycle: ChargingCycle) if startTime == cycle.startTime && duration < cycle.duration =>
          chargingSessionInternal.remove(chargingSessionInternal.size - 1)
          val cycle = ChargingCycle(startTime, energy, duration)
          chargingSessionInternal.append(cycle)
          Some(cycle)
        case Some(cycle: ChargingCycle) if startTime == cycle.startTime =>
          // keep existing charging cycle
          logger.info(s"the new charging cycle of vehicle $vehicle ends after the end of the last cycle")
          None
        case Some(cycle: ChargingCycle) if startTime < cycle.startTime =>
          // This should never happen
          logger.error(s"the new charging cycle of vehicle $vehicle starts before the start of the last cycle")
          None
        case _ =>
          val cycle = ChargingCycle(startTime, energy, duration)
          chargingSessionInternal.append(cycle)
          Some(cycle)
      }

    def computeSessionEnergy: Double = chargingSessionInternal.map(_.energy).sum
    def computeSessionDuration: Long = chargingSessionInternal.map(_.duration).sum

    def computeSessionEndTime: Int =
      if (sessionStartTime >= 0) {
        (sessionStartTime + computeSessionDuration).toInt
      } else {
        throw new RuntimeException("Can't compute session end time, if the sessions did not start yet")
      }

    def latestChargingCycle: Option[ChargingCycle] = chargingSessionInternal.lastOption
    def status: ConnectionStatus = statusInternal.last
  }
}
