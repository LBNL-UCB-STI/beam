package beam.agentsim.infrastructure

import akka.actor.ActorRef
import beam.agentsim.agents.vehicles.{BeamVehicle, VehicleManager}
import beam.agentsim.events.RefuelSessionEvent.{NotApplicable, ShiftStatus}
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.TAZ
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.Random

/**
  * Created by haitamlaarabi
  */
class ChargingNetwork[GEO: GeoLevel](
  vehicleManagerId: Id[VehicleManager],
  chargingZones: Map[Id[ParkingZoneId], ParkingZone[GEO]]
) extends ParkingNetwork[GEO](vehicleManagerId, chargingZones) {

  import ChargingNetwork._

  override protected val searchFunctions: Option[InfrastructureFunctions[_]] = None

  protected val chargingZoneKeyToChargingStationMap: Map[Id[ParkingZoneId], ChargingStation] =
    chargingZones.map { case (zoneId, zone) => zoneId -> ChargingStation(zone) }

  val chargingStations: List[ChargingStation] = chargingZoneKeyToChargingStationMap.values.toList

  /**
    * @return all vehicles still connected to a charging point
    */
  def connectedVehicles: Map[Id[BeamVehicle], ChargingVehicle] =
    chargingZoneKeyToChargingStationMap.flatMap(_._2.connectedVehicles)

  /**
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
      ParkingZone.constructParkingZoneKey(vehicleManagerId, geoId, parkingType, chargingPointType, pricingModel)
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
  def lookupVehicle(vehicleId: Id[BeamVehicle]): Option[ChargingVehicle] =
    chargingZoneKeyToChargingStationMap.values.view.flatMap(_.lookupVehicle(vehicleId)).headOption

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
  def attemptToConnectVehicle(
    tick: Int,
    vehicle: BeamVehicle,
    stall: ParkingStall,
    theSender: ActorRef,
    shiftStatus: ShiftStatus = NotApplicable
  ): Option[(ChargingVehicle, ConnectionStatus.Value)] = {
    lookupStation(stall)
      .map { x =>
        vehicle.useParkingStall(stall)
        Some(x.connect(tick, vehicle, stall, theSender, shiftStatus))
      }
      .getOrElse {
        logger.error(
          s"Cannot find a $vehicleManagerId station identified with tazId ${stall.tazId}, " +
          s"parkingType ${stall.parkingType} and chargingPointType ${stall.chargingPointType.get}!"
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
    val WaitingToCharge, Connected, Disconnected, AlreadyAtStation = Value
  }

  def apply[GEO: GeoLevel](
    vehicleManagerId: Id[VehicleManager],
    chargingZones: Map[Id[ParkingZoneId], ParkingZone[GEO]],
    geoQuadTree: QuadTree[GEO],
    idToGeoMapping: scala.collection.Map[Id[GEO], GEO],
    geoToTAZ: GEO => TAZ,
    envelopeInUTM: Envelope,
    beamConfig: BeamConfig,
    distanceFunction: (Coord, Coord) => Double,
    minSearchRadius: Double,
    maxSearchRadius: Double,
    seed: Int
  ): ChargingNetwork[GEO] = {
    new ChargingNetwork[GEO](
      vehicleManagerId,
      chargingZones
    ) {
      override val searchFunctions: Option[InfrastructureFunctions[_]] = Some(
        new ChargingFunctions[GEO](
          vehicleManagerId,
          geoQuadTree,
          idToGeoMapping,
          geoToTAZ,
          chargingZones,
          distanceFunction,
          minSearchRadius,
          maxSearchRadius,
          envelopeInUTM,
          seed,
          beamConfig.beam.agentsim.agents.parking.mulitnomialLogit
        )
      )
    }
  }

  def apply[GEO: GeoLevel](
    vehicleManagerId: Id[VehicleManager],
    parkingDescription: Iterator[String],
    geoQuadTree: QuadTree[GEO],
    idToGeoMapping: scala.collection.Map[Id[GEO], GEO],
    geoToTAZ: GEO => TAZ,
    envelopeInUTM: Envelope,
    beamConfig: BeamConfig,
    distanceFunction: (Coord, Coord) => Double,
    minSearchRadius: Double,
    maxSearchRadius: Double,
    seed: Int
  ): ChargingNetwork[GEO] = {
    val parking = ParkingZoneFileUtils.fromIterator(
      parkingDescription,
      vehicleManagerId,
      new Random(beamConfig.matsim.modules.global.randomSeed),
      1.0,
      1.0
    )
    ChargingNetwork[GEO](
      vehicleManagerId,
      parking.zones.toMap,
      geoQuadTree,
      idToGeoMapping,
      geoToTAZ,
      envelopeInUTM,
      beamConfig,
      distanceFunction,
      minSearchRadius,
      maxSearchRadius,
      seed
    )
  }

  def init(
    vehicleManagerId: Id[VehicleManager],
    chargingZones: Map[Id[ParkingZoneId], ParkingZone[TAZ]],
    envelopeInUTM: Envelope,
    beamServices: BeamServices
  ): ChargingNetwork[TAZ] = {
    ChargingNetwork[TAZ](
      vehicleManagerId,
      chargingZones,
      beamServices.beamScenario.tazTreeMap.tazQuadTree,
      beamServices.beamScenario.tazTreeMap.idToTAZMapping,
      identity[TAZ](_),
      envelopeInUTM: Envelope,
      beamServices.beamConfig,
      beamServices.geo.distUTMInMeters(_, _),
      beamServices.beamConfig.beam.agentsim.agents.parking.minSearchRadius,
      beamServices.beamConfig.beam.agentsim.agents.parking.maxSearchRadius,
      beamServices.beamConfig.matsim.modules.global.randomSeed
    )
  }

  def init(
    vehicleManagerId: Id[VehicleManager],
    chargingZones: Map[Id[ParkingZoneId], ParkingZone[Link]],
    geoQuadTree: QuadTree[Link],
    idToGeoMapping: scala.collection.Map[Id[Link], Link],
    geoToTAZ: Link => TAZ,
    envelopeInUTM: Envelope,
    beamServices: BeamServices
  ): ChargingNetwork[Link] = {
    ChargingNetwork[Link](
      vehicleManagerId,
      chargingZones,
      geoQuadTree,
      idToGeoMapping,
      geoToTAZ,
      envelopeInUTM,
      beamServices.beamConfig,
      beamServices.geo.distUTMInMeters(_, _),
      beamServices.beamConfig.beam.agentsim.agents.parking.minSearchRadius,
      beamServices.beamConfig.beam.agentsim.agents.parking.maxSearchRadius,
      beamServices.beamConfig.matsim.modules.global.randomSeed
    )
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

    def vehicles: scala.collection.Map[Id[BeamVehicle], ChargingVehicle] =
      waitingLineVehicles ++ connectedVehiclesInternal

    def lookupVehicle(vehicleId: Id[BeamVehicle]): Option[ChargingVehicle] =
      connectedVehiclesInternal.get(vehicleId).orElse(waitingLineInternal.find(_.vehicle.id == vehicleId))

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
      theSender: ActorRef,
      shiftStatus: ShiftStatus = NotApplicable
    ): (ChargingVehicle, ConnectionStatus.Value) =
      vehicles.get(vehicle.id) match {
        case Some(chargingVehicle) => (chargingVehicle, AlreadyAtStation)
        case _ =>
          val (sessionTime, status) = if (numAvailableChargers > 0) (tick, Connected) else (-1, WaitingToCharge)
          val listStatus = ListBuffer(status)
          val chargingVehicle =
            ChargingVehicle(vehicle, stall, this, tick, sessionTime, theSender, listStatus, shiftStatus = shiftStatus)
          status match {
            case Connected => connectedVehiclesInternal.put(vehicle.id, chargingVehicle)
            case _         => waitingLineInternal.enqueue(chargingVehicle)
          }
          (chargingVehicle, status)
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
    chargingSessions: ListBuffer[ChargingCycle] = ListBuffer.empty[ChargingCycle],
    shiftStatus: ShiftStatus = NotApplicable
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
