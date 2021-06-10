package beam.agentsim.infrastructure

import akka.actor.ActorRef
import beam.agentsim.agents.vehicles.{BeamVehicle, VehicleManager}
import beam.agentsim.infrastructure.ZonalParkingManager.mnlMultiplierParametersFromConfig
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.ZoneSearchTree
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.TAZ
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.Id
import org.matsim.core.utils.collections.QuadTree

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.Random

/**
  * Created by haitamlaarabi
  */
case class ChargingNetwork[GEO: GeoLevel](
  chargingZones: Map[Id[ParkingZoneId], ParkingZone[GEO]],
  vehicleManager: Option[Id[VehicleManager]]
) extends LazyLogging {
  import ChargingNetwork._

  private val chargingZoneKeyToChargingStationMap: Map[Id[ParkingZoneId], ChargingStation] =
    chargingZones.map { case (zoneId, zone) => zoneId -> ChargingStation(zone) }

  val chargingStations: List[ChargingStation] = chargingZoneKeyToChargingStationMap.values.toList

  /**
    *
    * @return all vehicles still connected to a charging point
    */
  def connectedVehicles: Map[Id[BeamVehicle], ChargingVehicle] =
    chargingZoneKeyToChargingStationMap.flatMap(_._2.connectedVehicles)

  /**
    *
    * @return all vehicles, connected, and the ones waiting in line
    */
  def vehicles: Map[Id[BeamVehicle], ChargingVehicle] = chargingZoneKeyToChargingStationMap.flatMap(_._2.vehicles)

  /**
    * lookup a station from attributes
    * @param tazId the taz id
    * @param parkingType the parking type
    * @param chargingPointType the charging type
    * @return
    */
  def lookupStation(
    geoId: Id[_],
    parkingType: ParkingType,
    chargingPointType: Option[ChargingPointType],
    pricingModel: Option[PricingModel]
  ): Option[ChargingStation] =
    chargingZoneKeyToChargingStationMap.get(
      ParkingZone.constructParkingZoneKey(vehicleManager, geoId, parkingType, chargingPointType, pricingModel)
    )

  /**
    * lookup
    * @param stall Parking Stall
    * @return
    */
  def lookupStation(stall: ParkingStall): Option[ChargingStation] =
    lookupStation(stall.geoId, stall.parkingType, stall.chargingPointType, stall.pricingModel)

  /**
    * lookup information about charging vehicle
    * @param vehicleId vehicle Id
    * @return charging vehicle
    */
  def lookupVehicle(vehicleId: Id[BeamVehicle]): Option[ChargingVehicle] = vehicles.get(vehicleId)

  /**
    * clear charging vehicle map
    */
  def clearAllMappedStations(): Unit =
    chargingZoneKeyToChargingStationMap.foreach(_._2.clearAllVehiclesFromTheStation())

  /**
    * Connect to charging point or add to waiting line
    * @param tick current time
    * @param vehicle vehicle to charge
    * @return a tuple of the status of the charging vehicle and the connection status
    */
  def attemptToConnectVehicle(tick: Int, vehicle: BeamVehicle, theSender: ActorRef): Option[ChargingVehicle] = {
    vehicle.stall match {
      case Some(stall) =>
        lookupStation(stall) match {
          case Some(station) => Some(station.connect(tick, vehicle, stall, theSender))
          case _ =>
            logger.error(
              s"CNM cannot find a $vehicleManager station identified with tazId ${stall.tazId}, parkingType ${stall.parkingType} and chargingPointType ${stall.chargingPointType.get}. Attention required!"
            )
            None
        }
      case _ =>
        logger.error(
          s"CNM cannot start charging the vehicle $vehicle that doesn't have a stall. Something if broken and need attention!"
        )
        None
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

  def init[GEO: GeoLevel](
    allZones: Map[Id[ParkingZoneId], ParkingZone[GEO]]
  ): (Vector[ChargingNetwork[GEO]], Map[Id[ParkingZoneId], ParkingZone[GEO]], ZoneSearchTree[GEO]) = {
    val chargingZones = allZones.filter(_._2.chargingPointType.isDefined)
    val chargingZoneTree = ParkingZoneFileUtils.createZoneSearchTree(chargingZones.values.toSeq)
    val chargingNetworks = chargingZones
      .groupBy(_._2.vehicleManager)
      .map { case (vehManagerMaybe, zones) => new ChargingNetwork(zones, vehManagerMaybe) }
      .toVector
    (chargingNetworks, chargingZones, chargingZoneTree)
  }

  def init[GEO: GeoLevel](
    allZones: Map[Id[ParkingZoneId], ParkingZone[GEO]],
    geoQuadTree: QuadTree[GEO],
    idToGeoMapping: scala.collection.Map[Id[GEO], GEO],
    geoToTAZ: GEO => TAZ,
    envelopeInUTM: Envelope,
    beamConfig: BeamConfig,
    geo: GeoUtils
  ): (Vector[ChargingNetwork[GEO]], ChargingFunctions[GEO]) = {
    val (chargingNetworks, chargingZones, chargingZoneTree) = init[GEO](allZones)
    val chargingFunctions = new ChargingFunctions[GEO](
      geoQuadTree,
      idToGeoMapping,
      geoToTAZ,
      geo,
      chargingZones,
      chargingZoneTree,
      new Random(beamConfig.matsim.modules.global.randomSeed),
      beamConfig.beam.agentsim.agents.parking.minSearchRadius,
      beamConfig.beam.agentsim.agents.parking.maxSearchRadius,
      envelopeInUTM,
      mnlMultiplierParametersFromConfig(beamConfig),
      beamConfig.beam.agentsim.chargingNetworkManager.chargingPoint
    )
    (chargingNetworks, chargingFunctions)
  }

  final case class ChargingStation(zone: ParkingZone[_]) {
    import ConnectionStatus._
    private val connectedVehiclesInternal = mutable.HashMap.empty[Id[BeamVehicle], ChargingVehicle]
    private val waitingLineInternal: mutable.PriorityQueue[ChargingVehicle] =
      mutable.PriorityQueue.empty[ChargingVehicle](Ordering.by((_: ChargingVehicle).arrivalTime).reverse)

    def numAvailableChargers: Int = zone.maxStalls - connectedVehiclesInternal.size
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
        val chargingVehicle = ChargingVehicle(vehicle, stall, this, tick, tick, theSender, ListBuffer(Connected))
        connectedVehiclesInternal.put(vehicle.id, chargingVehicle)
        chargingVehicle
      } else {
        val chargingVehicle = ChargingVehicle(vehicle, stall, this, tick, -1, theSender, ListBuffer(Waiting))
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
        val v = waitingLineInternal.dequeue().copy(sessionStartTime = tick)
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
    theSender: ActorRef,
    connectionStatus: ListBuffer[ConnectionStatus.ConnectionStatus],
    chargingSessions: ListBuffer[ChargingCycle] = ListBuffer.empty[ChargingCycle]
  ) extends LazyLogging {
    import ConnectionStatus._
    private[ChargingNetwork] def updateStatus(status: ConnectionStatus): ChargingVehicle = {
      connectionStatus.append(status)
      this
    }

    /**
      * adding a new charging cycle to the charging session
      * @param startTime start time of the charging cycle
      * @param energy energy delivered
      * @param duration duration of charging
      * @return boolean value expressing if the charging cycle has been added
      */
    def processChargingCycle(startTime: Int, energy: Double, duration: Int): Option[ChargingCycle] =
      chargingSessions.lastOption match {
        case Some(cycle: ChargingCycle) if startTime == cycle.startTime && duration < cycle.duration =>
          // this means that charging cycle was abruptly interrupted
          val cycle = ChargingCycle(startTime, energy, duration)
          chargingSessions.remove(chargingSessions.length - 1)
          chargingSessions.append(cycle)
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
          chargingSessions.append(cycle)
          Some(cycle)
      }

    def latestChargingCycle: Option[ChargingCycle] = chargingSessions.lastOption
    def computeSessionEnergy: Double = chargingSessions.map(_.energy).sum
    def computeSessionDuration: Long = chargingSessions.map(_.duration).sum

    def computeSessionEndTime: Int =
      if (sessionStartTime >= 0) {
        (sessionStartTime + computeSessionDuration).toInt
      } else {
        throw new RuntimeException("Can't compute session end time, if the sessions did not start yet")
      }

  }
}
