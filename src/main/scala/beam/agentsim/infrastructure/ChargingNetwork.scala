package beam.agentsim.infrastructure

import akka.actor.ActorRef
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.events.RefuelSessionEvent.{NotApplicable, ShiftStatus}
import beam.agentsim.infrastructure.ChargingNetworkManager.ChargingPlugRequest
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.TAZ
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.Random

/**
  * Created by haitamlaarabi
  */
class ChargingNetwork[GEO: GeoLevel](val chargingZones: Map[Id[ParkingZoneId], ParkingZone[GEO]])
    extends ParkingNetwork[GEO](chargingZones) {

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
    * all vehicles waiting in line at a charging point
    * @return
    */
  def waitingLineVehicles: Map[Id[BeamVehicle], ChargingVehicle] =
    chargingZoneKeyToChargingStationMap.flatMap(_._2.waitingLineVehiclesMap)

  /**
    * @return all vehicles, connected, and the ones waiting in line
    */
  def vehicles: Map[Id[BeamVehicle], ChargingVehicle] = chargingZoneKeyToChargingStationMap.flatMap(_._2.vehicles)

  /**
    * lookup a station from parking zone Id
    * @param parkingZoneId parking zone Id
    * @return
    */
  def lookupStation(parkingZoneId: Id[ParkingZoneId]): Option[ChargingStation] =
    chargingZoneKeyToChargingStationMap.get(parkingZoneId)

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
    * @param request ChargingPlugRequest
    * @param theSender ActorRef
    * @return a tuple of the status of the charging vehicle and the connection status
    */
  def processChargingPlugRequest(
    request: ChargingPlugRequest,
    activityType: String,
    theSender: ActorRef
  ): Option[ChargingVehicle] = {
    lookupStation(request.stall.parkingZoneId)
      .map(
        _.connect(
          request.tick,
          request.vehicle,
          request.stall,
          request.personId,
          activityType,
          request.shiftStatus,
          request.shiftDuration,
          theSender
        )
      )
      .orElse {
        logger.error(
          s"Cannot find a ${request.stall.reservedFor} station identified with tazId ${request.stall.tazId}, " +
          s"parkingType ${request.stall.parkingType} and chargingPointType ${request.stall.chargingPointType.get}!"
        )
        None
      }
  }

  /**
    * @param vehicleId vehicle to end charge
    * @param tick at time
    * @return
    */
  def endChargingSession(vehicleId: Id[BeamVehicle], tick: Int): Option[ChargingVehicle] = {
    lookupVehicle(vehicleId) map { chargingVehicle =>
      chargingVehicle.chargingStation.endCharging(chargingVehicle.vehicle.id, tick)
    } getOrElse {
      logger.debug(s"Vehicle $vehicleId has already ended charging")
      None
    }
  }

  /**
    * Disconnect the vehicle for the charging point/station
    * @param vehicleId vehicle to disconnect
    * @return a tuple of the status of the charging vehicle and the connection status
    */
  def disconnectVehicle(vehicleId: Id[BeamVehicle], tick: Int): Option[ChargingVehicle] = {
    lookupVehicle(vehicleId) map { chargingVehicle =>
      chargingVehicle.chargingStation.disconnect(chargingVehicle.vehicle.id, tick)
    } getOrElse {
      logger.debug(s"Vehicle $vehicleId is already disconnected")
      None
    }
  }

  /**
    * transfer vehciles from waiting line to connected
    * @param station the corresponding station
    * @return list of vehicle that connected
    */
  def processWaitingLine(tick: Int, station: ChargingStation): List[ChargingVehicle] =
    station.connectFromWaitingLine(tick)
}

object ChargingNetwork extends LazyLogging {

  case class ChargingStatus(status: ChargingStatus.ChargingStatusEnum, time: Int)

  object ChargingStatus extends Enumeration {
    type ChargingStatusEnum = Value
    val WaitingAtStation, Connected, Disconnected, GracePeriod = Value
  }

  def apply[GEO: GeoLevel](
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
    new ChargingNetwork[GEO](chargingZones) {
      override val searchFunctions: Option[InfrastructureFunctions[_]] = Some(
        new ChargingFunctions[GEO](
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
    parkingDescription: Iterator[String],
    geoQuadTree: QuadTree[GEO],
    idToGeoMapping: scala.collection.Map[Id[GEO], GEO],
    geoToTAZ: GEO => TAZ,
    envelopeInUTM: Envelope,
    beamConfig: BeamConfig,
    beamServicesMaybe: Option[BeamServices],
    distanceFunction: (Coord, Coord) => Double,
    minSearchRadius: Double,
    maxSearchRadius: Double,
    seed: Int
  ): ChargingNetwork[GEO] = {
    val parking = ParkingZoneFileUtils.fromIterator(
      parkingDescription,
      Some(beamConfig),
      beamServicesMaybe,
      new Random(beamConfig.matsim.modules.global.randomSeed),
      None,
      1.0,
      1.0
    )
    ChargingNetwork[GEO](
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
    chargingZones: Map[Id[ParkingZoneId], ParkingZone[TAZ]],
    envelopeInUTM: Envelope,
    beamServices: BeamServices
  ): ChargingNetwork[TAZ] = {
    ChargingNetwork[TAZ](
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
    chargingZones: Map[Id[ParkingZoneId], ParkingZone[Link]],
    geoQuadTree: QuadTree[Link],
    idToGeoMapping: scala.collection.Map[Id[Link], Link],
    geoToTAZ: Link => TAZ,
    envelopeInUTM: Envelope,
    beamServices: BeamServices
  ): ChargingNetwork[Link] = {
    ChargingNetwork[Link](
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
    import ChargingStatus._
    private val chargingVehiclesInternal = mutable.HashMap.empty[Id[BeamVehicle], ChargingVehicle]

    private val vehiclesInGracePeriodAfterCharging = mutable.HashMap.empty[Id[BeamVehicle], ChargingVehicle]

    private var waitingLineInternal: mutable.PriorityQueue[ChargingVehicle] =
      mutable.PriorityQueue.empty[ChargingVehicle](Ordering.by((_: ChargingVehicle).arrivalTime).reverse)

    private[ChargingNetwork] def numAvailableChargers: Int =
      zone.maxStalls - howManyVehiclesAreCharging - howManyVehiclesAreInGracePeriodAfterCharging

    private[ChargingNetwork] def connectedVehicles: Map[Id[BeamVehicle], ChargingVehicle] =
      chargingVehiclesInternal.toMap

    def howManyVehiclesAreWaiting: Int = waitingLineInternal.size
    def howManyVehiclesAreCharging: Int = chargingVehiclesInternal.size
    def howManyVehiclesAreInGracePeriodAfterCharging: Int = vehiclesInGracePeriodAfterCharging.size

    private[ChargingNetwork] def waitingLineVehiclesMap: scala.collection.Map[Id[BeamVehicle], ChargingVehicle] =
      waitingLineInternal.map(x => x.vehicle.id -> x).toMap

    private[ChargingNetwork] def vehicles: scala.collection.Map[Id[BeamVehicle], ChargingVehicle] =
      waitingLineVehiclesMap ++ chargingVehiclesInternal

    private[ChargingNetwork] def lookupVehicle(vehicleId: Id[BeamVehicle]): Option[ChargingVehicle] =
      chargingVehiclesInternal
        .get(vehicleId)
        .orElse(vehiclesInGracePeriodAfterCharging.get(vehicleId))
        .orElse(waitingLineInternal.find(_.vehicle.id == vehicleId))

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
      personId: Id[Person],
      activityType: String,
      shiftStatus: ShiftStatus = NotApplicable,
      shiftDuration: Option[Int] = None,
      theSender: ActorRef
    ): ChargingVehicle = {
      vehicles.get(vehicle.id) match {
        case Some(chargingVehicle) =>
          logger.error("Trying to connect a vehicle already connected. Something is broken!")
          chargingVehicle
        case _ =>
          val chargingVehicle =
            ChargingVehicle(vehicle, stall, this, tick, personId, activityType, shiftStatus, shiftDuration, theSender)
          if (numAvailableChargers > 0) {
            chargingVehiclesInternal.put(vehicle.id, chargingVehicle)
            chargingVehicle.updateStatus(Connected, tick)
          } else {
            waitingLineInternal.enqueue(chargingVehicle)
            chargingVehicle.updateStatus(WaitingAtStation, tick)
          }
      }
    }

    /**
      * remove vehicle from connected list and disconnect from charging point
      * @param vehicleId vehicle to disconnect
      * @return status of connection
      */
    private[infrastructure] def endCharging(vehicleId: Id[BeamVehicle], tick: Int): Option[ChargingVehicle] =
      this.synchronized {
        chargingVehiclesInternal.remove(vehicleId).map { v =>
          vehiclesInGracePeriodAfterCharging.put(vehicleId, v)
          v.updateStatus(GracePeriod, tick)
        }
      }

    /**
      * remove vehicle from connected list and disconnect from charging point
      * @param vehicleId vehicle to disconnect
      * @return status of connection
      */
    private[ChargingNetwork] def disconnect(vehicleId: Id[BeamVehicle], tick: Int): Option[ChargingVehicle] =
      this.synchronized {
        chargingVehiclesInternal
          .remove(vehicleId)
          .map(_.updateStatus(Disconnected, tick))
          .orElse(vehiclesInGracePeriodAfterCharging.remove(vehicleId).map(_.updateStatus(Disconnected, tick)))
          .orElse {
            waitingLineInternal.find(_.vehicle.id == vehicleId) map { chargingVehicle =>
              waitingLineInternal = waitingLineInternal.filterNot(_.vehicle.id == vehicleId)
              chargingVehicle
            }
          }
      }

    /**
      * process waiting line by removing vehicle from waiting line and adding it to the connected list
      * @return map of vehicles that got connected
      */
    private[ChargingNetwork] def connectFromWaitingLine(tick: Int): List[ChargingVehicle] = this.synchronized {
      (1 to Math.min(waitingLineInternal.size, numAvailableChargers)).map { _ =>
        val v = waitingLineInternal.dequeue()
        chargingVehiclesInternal.put(v.vehicle.id, v)
        v.updateStatus(Connected, tick)
      }.toList
    }

    private[ChargingNetwork] def clearAllVehiclesFromTheStation(): Unit = {
      chargingVehiclesInternal.clear()
      waitingLineInternal.clear()
      vehiclesInGracePeriodAfterCharging.clear()
    }
  }

  final case class ChargingCycle(
    startTime: Int,
    endTime: Int,
    energyToCharge: Double,
    energyToChargeIfUnconstrained: Double,
    maxDuration: Int
  ) {
    var refueled: Boolean = false
  }

  final case class ChargingVehicle(
    vehicle: BeamVehicle,
    stall: ParkingStall,
    chargingStation: ChargingStation,
    arrivalTime: Int,
    personId: Id[Person],
    activityType: String,
    shiftStatus: ShiftStatus,
    shiftDuration: Option[Int],
    theSender: ActorRef,
    chargingStatus: ListBuffer[ChargingStatus] = ListBuffer.empty[ChargingStatus],
    chargingSessions: ListBuffer[ChargingCycle] = ListBuffer.empty[ChargingCycle]
  ) extends LazyLogging {
    import ChargingStatus._

    val chargingShouldEndAt: Option[Int] = shiftDuration.map(_ + arrivalTime)

    /**
      * @param status the new connection status
      * @return
      */
    private[ChargingNetwork] def updateStatus(status: ChargingStatus.ChargingStatusEnum, time: Int): ChargingVehicle = {
      status match {
        case WaitingAtStation =>
          vehicle.waitingToCharge(time)
          logger.debug(s"Vehicle ${vehicle.id} has been added to waiting queue for charging")
        case Connected =>
          vehicle.connectToChargingPoint(time)
          logger.debug(s"Vehicle ${vehicle.id} has been connected to charging point")
        case Disconnected =>
          vehicle.disconnectFromChargingPoint()
          logger.debug(s"Vehicle ${vehicle.id} has been disconnected from charging point")
        case GracePeriod =>
          logger.debug(s"Vehicle ${vehicle.id} is in grace period now")
        case _ => // No change to vehicle BeamVehicle as for now
      }
      chargingStatus.append(ChargingStatus(status, time))
      this
    }

    /**
      * @return
      */
    def refuel: Option[ChargingCycle] = {
      chargingSessions.lastOption match {
        case Some(cycle @ ChargingCycle(_, _, energy, _, _)) if !cycle.refueled =>
          vehicle.addFuel(energy)
          cycle.refueled = true
          logger.debug(s"Charging vehicle $vehicle. Provided energy of = $energy J")
          Some(cycle)
        case _ => None
      }
    }

    /**
      * an unplug request arrived right before the new cycle started
      * or vehicle finished charging right before unplug requests arrived
      * @param newEndTime Int
      */
    def checkAndCorrectCycleAfterInterruption(newEndTime: Int): Unit = {
      while (chargingSessions.lastOption.exists(_.endTime > newEndTime)) {
        val cycle = chargingSessions.last
        if (cycle.refueled) {
          vehicle.addFuel(-1 * cycle.energyToCharge)
          logger.debug(s"Deleting cycle $cycle for vehicle $vehicle due to an interruption!")
        }
        chargingSessions.remove(chargingSessions.length - 1)
      }
    }

    /**
      * adding a new charging cycle to the charging session
      * @param startTime start time of the charging cycle
      * @param energy energy delivered
      * @param endTime endTime of charging
      * @return boolean value expressing if the charging cycle has been added
      */
    def processCycle(
      startTime: Int,
      endTime: Int,
      energy: Double,
      energyToChargeIfUnconstrained: Double,
      maxDuration: Int
    ): Option[ChargingCycle] = {
      val addNewChargingCycle = chargingSessions.lastOption match {
        case None =>
          // first charging cycle
          true
        case Some(cycle)
            if startTime >= cycle.endTime && chargingStatus.last.status == Connected || (chargingStatus.last.status == Disconnected && chargingStatus.last.time >= endTime) =>
          // either a new cycle or an unplug cycle arriving in the middle of the current cycle
          true
        // other cases where an unnecessary charging session happens when a vehicle is already charged or unplugged
        case _ =>
          logger.debug(
            "Either Vehicle {} at Stall: {} had been disconnected before the charging cycle." +
            "last charging cycle end time was {} while the current charging cycle end time is {}",
            vehicle.id,
            stall,
            chargingSessions.lastOption.map(_.endTime).getOrElse(-1),
            endTime
          )
          logger.debug(
            "Or the unplug request event for Vehicle {} arrived after it finished charging at time {}",
            vehicle.id,
            endTime
          )
          false
      }
      if (addNewChargingCycle) {
        val newCycle = ChargingCycle(startTime, endTime, energy, energyToChargeIfUnconstrained, maxDuration)
        chargingSessions.append(newCycle)
        Some(newCycle)
      } else None
    }

    /**
      * @return
      */
    def calculateChargingSessionLengthAndEnergyInJoule: (Long, Double) = chargingSessions.foldLeft((0L, 0.0)) {
      case ((accA, accB), charging) => (accA + (charging.endTime - charging.startTime), accB + charging.energyToCharge)
    }
  }
}
