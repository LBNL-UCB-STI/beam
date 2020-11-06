package beam.agentsim.infrastructure

import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.infrastructure.ChargingNetworkManager.{ChargingZone, VehicleManager}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.core.utils.collections.QuadTree

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable

class ChargingNetwork(vehicleManager: VehicleManager, chargingStationsQTree: QuadTree[ChargingZone])
    extends LazyLogging {
  import ChargingNetwork._
  import ChargingStatus._

  val chargingStationsMap: Map[ChargingZone, ChargingStation] =
    chargingStationsQTree.values().asScala.map(z => z -> ChargingStation(z)).toMap

  private val vehiclesToCharge: TrieMap[Id[BeamVehicle], ChargingVehicle] = new TrieMap()

  def lookupStation(stall: ParkingStall): Option[ChargingStation] =
    lookupStation(ChargingZone.toChargingZoneInstance(stall, vehicleManager))

  def lookupStation(chargingZone: ChargingZone): Option[ChargingStation] = chargingStationsMap.get(chargingZone)

  def lookupVehicle(vehicleId: Id[BeamVehicle]): Option[ChargingVehicle] = vehiclesToCharge.get(vehicleId)

  def lookupAllVehicles(): List[ChargingVehicle] = vehiclesToCharge.values.toList

  def getVehicleManager: VehicleManager = vehicleManager

  /**
    *
    * @param vehicleId
    * @param latestChargingSession
    * @return
    */
  def update(vehicleId: Id[BeamVehicle], latestChargingSession: ChargingSession): ChargingVehicle = {
    val temp = vehiclesToCharge(vehicleId)
    val updated = temp.copy(
      totalChargingSession = temp.totalChargingSession.combine(latestChargingSession),
      lastChargingSession = latestChargingSession
    )
    vehiclesToCharge.update(vehicleId, updated)
    updated
  }

  def clear(): Unit = vehiclesToCharge.clear()

  /**
    *
    * @param tick
    * @param vehicle
    * @param stall
    * @return
    */
  def connectVehicle(
    tick: Int,
    vehicle: BeamVehicle,
    stall: ParkingStall
  ): (ChargingVehicle, ChargingStatus) = {
    val station = lookupStation(stall).get
    val chargingVehicle = ChargingVehicle(
      vehicle,
      vehicleManager,
      stall,
      station,
      totalChargingSession = ChargingSession(),
      lastChargingSession = ChargingSession()
    )
    vehiclesToCharge.put(vehicle.id, chargingVehicle)
    chargingVehicle -> station.connectVehicle(tick, vehicle)
  }

  /**
    *
    * @param tick
    * @param vehicleId
    * @return
    */
  def disconnectVehicle(tick: Int, vehicleId: Id[BeamVehicle]): Option[(ChargingVehicle, ChargingStatus)] = {
    vehiclesToCharge
      .remove(vehicleId)
      .map(
        chargingVehicle =>
          chargingVehicle -> lookupStation(chargingVehicle.stall).get.disconnectVehicle(tick, vehicleId)
      )
  }

  def processWaitingLine(tick: Int): Map[Id[BeamVehicle], ChargingStatus] =
    chargingStationsMap.flatMap(_._2.processWaitingLine(tick))
}

object ChargingNetwork {

  final case class ChargingStation(zone: ChargingZone) {
    import ChargingStatus._

    private val connectedVehiclesInternal = mutable.Map.empty[Id[BeamVehicle], BeamVehicle]
    private val waitingLineInternal: mutable.PriorityQueue[(Int, BeamVehicle)] =
      mutable.PriorityQueue.empty[(Int, BeamVehicle)](Ordering.by((_: (Int, BeamVehicle))._1).reverse)

    def numAvailableChargers: Int = zone.numChargers - connectedVehiclesInternal.size
    def connectedVehicles: List[BeamVehicle] = connectedVehiclesInternal.values.toList
    def waitingLine: List[BeamVehicle] = waitingLineInternal.toList.map(_._2)

    /**
      *
      * @param tick
      * @param vehicle
      * @return
      */
    private[ChargingNetwork] def connectVehicle(tick: Int, vehicle: BeamVehicle): ChargingStatus = {
      if (numAvailableChargers > 0) {
        connectedVehiclesInternal.put(vehicle.id, vehicle)
        vehicle.connectToChargingPoint(tick)
        ChargingStatus.Connected
      } else {
        waitingLineInternal.enqueue((tick, vehicle))
        ChargingStatus.Waiting
      }
    }

    /**
      *
      * @param tick
      * @param vehicleId
      * @return
      */
    private[ChargingNetwork] def disconnectVehicle(tick: Int, vehicleId: Id[BeamVehicle]): ChargingStatus = {
      connectedVehiclesInternal
        .remove(vehicleId)
        .map { vehicle =>
          vehicle.disconnectFromChargingPoint()
          ChargingStatus.Disconnected
        }
        .getOrElse[ChargingStatus](ChargingStatus.NotConnected)
    }

    private[ChargingNetwork] def processWaitingLine(tick: Int): Map[Id[BeamVehicle], ChargingStatus] = {
      (1 to Math.min(waitingLineInternal.size, numAvailableChargers)).map { _ =>
        val (_, vehicle) = waitingLineInternal.dequeue()
        connectedVehiclesInternal.put(vehicle.id, vehicle)
        vehicle.connectToChargingPoint(tick)
        vehicle.id -> ChargingStatus.Connected
      }.toMap
    }
  }

  final case class ChargingSession(energy: Double = 0.0, duration: Long = 0) {

    def combine(other: ChargingSession): ChargingSession = ChargingSession(
      energy = this.energy + other.energy,
      duration = this.duration + other.duration
    )
  }

  object ChargingStatus extends Enumeration {
    type ChargingStatus = Value
    val Waiting, Connected, Disconnected, NotConnected = Value
  }

  final case class ChargingVehicle(
    vehicle: BeamVehicle,
    vehicleManager: VehicleManager,
    stall: ParkingStall,
    chargingStation: ChargingStation,
    totalChargingSession: ChargingSession,
    lastChargingSession: ChargingSession
  )
}
