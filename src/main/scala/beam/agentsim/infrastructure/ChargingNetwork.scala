package beam.agentsim.infrastructure

import akka.actor.ActorRef
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.vehicles.FuelType.FuelType
import beam.agentsim.events.RefuelSessionEvent.{NotApplicable, ShiftStatus}
import beam.agentsim.infrastructure.ChargingNetworkManager.ChargingPlugRequest
import beam.agentsim.infrastructure.ParkingInquiry.ParkingActivityType
import beam.agentsim.infrastructure.ParkingInquiry.ParkingSearchMode.EnRouteCharging
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.power.PowerManager.PowerInKW
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.skim.Skims
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import beam.utils.metrics.SimpleCounter
import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.Random

/**
  * Created by haitamlaarabi
  */
class ChargingNetwork(val parkingZones: Map[Id[ParkingZoneId], ParkingZone]) extends ParkingNetwork(parkingZones) {

  import ChargingNetwork._

  override protected val searchFunctions: Option[InfrastructureFunctions] = None

  override def processParkingInquiry(
    inquiry: ParkingInquiry,
    doNotReserveStallWithoutChargingPoint: Boolean = true,
    parallelizationCounterOption: Option[SimpleCounter] = None
  ): ParkingInquiryResponse = {
    val parkingResponse = super[ParkingNetwork].processParkingInquiry(
      inquiry,
      doNotReserveStallWithoutChargingPoint,
      parallelizationCounterOption
    )
    if (parkingResponse.stall.chargingPointType.isDefined)
      processVehicleOnTheWayToStation(inquiry, parkingResponse.stall)
    parkingResponse
  }

  private val beamVehicleIdToChargingVehicleMap: mutable.HashMap[Id[BeamVehicle], ChargingVehicle] =
    mutable.HashMap.empty

  private val chargingZoneKeyToChargingStationMap: Map[Id[ParkingZoneId], ChargingStation] =
    parkingZones.filter(_._2.chargingPointType.isDefined).map { case (zoneId, zone) => zoneId -> ChargingStation(zone) }

  val chargingStations: List[ChargingStation] = chargingZoneKeyToChargingStationMap.values.toList

  def stationMap(parkingZoneId: Id[ParkingZoneId]): Option[ChargingStation] =
    chargingZoneKeyToChargingStationMap.get(parkingZoneId)

  /**
    * all vehicles waiting in line at a charging point
    *
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
    *
    * @param parkingZoneId parking zone Id
    * @return
    */
  def lookupStation(parkingZoneId: Id[ParkingZoneId]): Option[ChargingStation] =
    chargingZoneKeyToChargingStationMap.get(parkingZoneId)

  /**
    * lookup information about charging vehicle
    *
    * @param vehicleId vehicle Id
    * @return charging vehicle
    */
  def lookupVehicle(vehicleId: Id[BeamVehicle]): Option[ChargingVehicle] =
    beamVehicleIdToChargingVehicleMap.get(vehicleId)

  /**
    * clear charging vehicle map
    */
  def clearAllMappedStations(): Unit =
    chargingZoneKeyToChargingStationMap.foreach(_._2.clearAllVehiclesFromTheStation())

  def processVehicleOnTheWayToStation(inquiry: ParkingInquiry, parkingStall: ParkingStall): Unit =
    chargingZoneKeyToChargingStationMap(parkingStall.parkingZoneId).expect(inquiry)

  /**
    * Connect to charging point or add to waiting line
    *
    * @param request   ChargingPlugRequest
    * @param theSender ActorRef
    * @return a tuple of the status of the charging vehicle and the connection status
    */
  def processChargingPlugRequest(
    request: ChargingPlugRequest,
    estimatedMinParkingDurationInSeconds: Int,
    endOfChargingInSeconds: Option[Int],
    theSender: ActorRef
  ): Option[ChargingVehicle] = lookupStation(request.stall.parkingZoneId)
    .map { chargingStation =>
      val chargingVehicle = chargingStation.connect(
        request.tick,
        request.vehicle,
        request.stall,
        request.personId,
        estimatedMinParkingDurationInSeconds,
        request.shiftStatus,
        request.shiftDuration,
        endOfChargingInSeconds,
        theSender
      )
      beamVehicleIdToChargingVehicleMap.put(chargingVehicle.vehicle.id, chargingVehicle)
      chargingVehicle
    }

  /**
    * Disconnect the vehicle for the charging point/station
    *
    * @param vehicleId vehicle to disconnect
    * @return a tuple of the status of the charging vehicle and the connection status
    */
  def disconnectVehicle(vehicleId: Id[BeamVehicle], tick: Int): Option[ChargingVehicle] = {
    lookupVehicle(vehicleId) map { chargingVehicle =>
      beamVehicleIdToChargingVehicleMap.remove(vehicleId)
      chargingVehicle.chargingStation.disconnect(chargingVehicle.vehicle.id, tick)
    } getOrElse {
      logger.debug(s"Vehicle $vehicleId is already disconnected")
      None
    }
  }

  /**
    * transfer vehciles from waiting line to connected
    *
    * @param station the corresponding station
    * @return list of vehicle that connected
    */
  def processWaitingLine(tick: Int, station: ChargingStation): List[ChargingVehicle] =
    station.connectFromWaitingLine(tick)
}

object ChargingNetwork extends LazyLogging {

  val EnRouteLabel: String = "EnRoute-"

  case class ChargingStatus(status: ChargingStatus.ChargingStatusEnum, time: Int)

  object ChargingStatus extends Enumeration {
    type ChargingStatusEnum = Value
    val WaitingAtStation, Connected, Disconnected, GracePeriod = Value
  }

  def apply(
    chargingZones: Map[Id[ParkingZoneId], ParkingZone],
    geoQuadTree: QuadTree[TAZ],
    idToGeoMapping: scala.collection.Map[Id[TAZ], TAZ],
    envelopeInUTM: Envelope,
    beamConfig: BeamConfig,
    distanceFunction: (Coord, Coord) => Double,
    skims: Option[Skims],
    fuelPrice: Map[FuelType, Double]
  ): ChargingNetwork = {
    new ChargingNetwork(chargingZones) {
      override val searchFunctions: Option[InfrastructureFunctions] = Some(
        new ChargingFunctions(
          geoQuadTree,
          idToGeoMapping,
          chargingZones,
          distanceFunction,
          beamConfig.beam.agentsim.agents.parking.minSearchRadius,
          beamConfig.beam.agentsim.agents.parking.maxSearchRadius,
          beamConfig.beam.agentsim.agents.parking.searchMaxDistanceRelativeToEllipseFoci,
          beamConfig.beam.agentsim.agents.vehicles.enroute.estimateOfMeanChargingDurationInSecond,
          beamConfig.beam.agentsim.agents.parking.fractionOfSameTypeZones,
          beamConfig.beam.agentsim.agents.parking.minNumberOfSameTypeZones,
          envelopeInUTM,
          beamConfig.matsim.modules.global.randomSeed,
          beamConfig.beam.agentsim.agents.parking.multinomialLogit,
          skims,
          fuelPrice,
          beamConfig.beam.agentsim.agents.parking.estimatedMinParkingDurationInSeconds
        )
      )
    }
  }

  def apply(
    parkingDescription: Iterator[String],
    geoQuadTree: QuadTree[TAZ],
    idToGeoMapping: scala.collection.Map[Id[TAZ], TAZ],
    envelopeInUTM: Envelope,
    beamConfig: BeamConfig,
    beamServicesMaybe: Option[BeamServices],
    distanceFunction: (Coord, Coord) => Double,
    skims: Option[Skims] = None,
    fuelPrice: Map[FuelType, Double] = Map()
  ): ChargingNetwork = {
    val parking = ParkingZoneFileUtils.fromIterator(
      parkingDescription,
      Some(beamConfig),
      beamServicesMaybe,
      new Random(beamConfig.matsim.modules.global.randomSeed),
      None,
      1.0,
      1.0
    )
    ChargingNetwork(
      parking.zones.toMap,
      geoQuadTree,
      idToGeoMapping,
      envelopeInUTM,
      beamConfig,
      distanceFunction,
      skims,
      fuelPrice
    )
  }

  def init(
    chargingZones: Map[Id[ParkingZoneId], ParkingZone],
    envelopeInUTM: Envelope,
    beamServices: BeamServices
  ): ChargingNetwork = {
    ChargingNetwork(
      chargingZones,
      beamServices.beamScenario.tazTreeMap.tazQuadTree,
      beamServices.beamScenario.tazTreeMap.idToTAZMapping,
      envelopeInUTM,
      beamServices.beamConfig,
      beamServices.geo.distUTMInMeters(_, _),
      Some(beamServices.skims),
      beamServices.beamScenario.fuelTypePrices
    )
  }

  final case class ChargingStation(zone: ParkingZone) {

    import ChargingStatus._

    val maxPlugPower: PowerInKW =
      ChargingPointType.getChargingPointInstalledPowerInKw(zone.chargingPointType.get)
    val numPlugs: Int = zone.maxStalls
    private val chargingVehiclesInternal = mutable.HashMap.empty[Id[BeamVehicle], ChargingVehicle]
    private val vehiclesInGracePeriodAfterCharging = mutable.HashMap.empty[Id[BeamVehicle], ChargingVehicle]
    private val parkingInquiries: mutable.HashMap[Id[BeamVehicle], ParkingInquiry] = mutable.HashMap.empty

    // priority queue is first come first serve
    // in previous iteration we used the remaining state of charge
    // mutable.PriorityQueue.empty[ChargingQueueEntry](Ordering.by[ChargingQueueEntry, Double](_.priority))
    private var waitingLineInternal: mutable.PriorityQueue[ChargingVehicle] =
      mutable.PriorityQueue.empty[ChargingVehicle](Ordering.by((_: ChargingVehicle).arrivalTime).reverse)

    def numAvailableChargers: Int =
      zone.maxStalls - howManyVehiclesAreCharging - howManyVehiclesAreInGracePeriodAfterCharging

    private[infrastructure] def connectedVehicles: collection.Map[Id[BeamVehicle], ChargingVehicle] =
      chargingVehiclesInternal

    def howManyVehiclesAreWaiting: Int = waitingLineInternal.size

    def howManyVehiclesAreCharging: Int = chargingVehiclesInternal.size

    def howManyVehiclesAreInGracePeriodAfterCharging: Int = vehiclesInGracePeriodAfterCharging.size

    def howManyVehiclesOnTheWayToStation: Int = parkingInquiries.size

    def remainingChargeDurationFromPluggedInVehicles(tick: Int): Int = {
      chargingVehiclesInternal.map { case (_, chargingVehicle) =>
        chargingVehicle.chargingExpectedToEndAt - tick
      }.sum
    }

    def remainingChargeDurationForVehiclesFromQueue: Int = waitingLineInternal.map(_.chargingExpectedToEndAt).sum

    private[ChargingNetwork] def waitingLineVehiclesMap: scala.collection.Map[Id[BeamVehicle], ChargingVehicle] =
      waitingLineInternal.map(x => x.vehicle.id -> x).toMap

    private[ChargingNetwork] def vehicles: scala.collection.Map[Id[BeamVehicle], ChargingVehicle] =
      waitingLineVehiclesMap ++ chargingVehiclesInternal

    private[ChargingNetwork] def expect(inquiry: ParkingInquiry): Unit =
      parkingInquiries.put(inquiry.beamVehicle.get.id, inquiry)

    /**
      * add vehicle to connected list and connect to charging point
      *
      * @param tick    current time
      * @param vehicle vehicle to connect
      * @return status of connection
      */
    private[ChargingNetwork] def connect(
      tick: Int,
      vehicle: BeamVehicle,
      stall: ParkingStall,
      personId: Id[Person],
      estimatedMinParkingDurationInSeconds: Int,
      shiftStatus: ShiftStatus = NotApplicable,
      shiftDuration: Option[Int] = None,
      endOfChargingInSeconds: Option[Int],
      theSender: ActorRef
    ): ChargingVehicle = {
      val (estimatedParkingDuration, activityType) = parkingInquiries
        .remove(vehicle.id)
        .map { inquiry =>
          val activityTypeAlias = if (inquiry.searchMode == EnRouteCharging) EnRouteLabel else ""
          val parkingDuration = endOfChargingInSeconds
            .map(_ - tick)
            .map(endOfChargingDuration => Math.min(endOfChargingDuration, inquiry.parkingDuration.toInt))
            .getOrElse(inquiry.parkingDuration.toInt)
          val updatedParkingDuration = Math.max(parkingDuration, estimatedMinParkingDurationInSeconds)
          (updatedParkingDuration, activityTypeAlias + inquiry.activityType)
        }
        .getOrElse((estimatedMinParkingDurationInSeconds, ParkingActivityType.Wherever.toString))
      vehicles.get(vehicle.id) match {
        case Some(chargingVehicle) =>
          logger.error(
            s"Something is broken! Trying to connect a vehicle already connected at time $tick: vehicle $vehicle - " +
            s"activityType $activityType - stall $stall - personId $personId - chargingInfo $chargingVehicle"
          )
          chargingVehicle
        case _ =>
          val chargingVehicle =
            ChargingVehicle(
              vehicle,
              stall,
              this,
              tick,
              vehicle.primaryFuelLevelInJoules,
              personId,
              estimatedParkingDuration,
              activityType,
              shiftStatus,
              shiftDuration,
              theSender
            )
          if (numAvailableChargers > 0) {
            chargingVehiclesInternal.put(vehicle.id, chargingVehicle)
            chargingVehicle.updateStatus(Connected, tick)
          } else {
            logger.info(
              s"Vehicle at waiting line, time $tick: vehicle $vehicle - " +
              s"activityType $activityType - stall $stall - personId $personId - chargingInfo $chargingVehicle"
            )
            waitingLineInternal.enqueue(chargingVehicle)
            chargingVehicle.updateStatus(WaitingAtStation, tick)
          }
      }
    }

    /**
      * remove vehicle from connected list and disconnect from charging point
      *
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
      *
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
      *
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
      parkingInquiries.clear()
    }
  }

  final case class ChargingCycle(
    startTime: Int,
    endTime: Int,
    powerInKW: Double,
    energyToCharge: Double,
    energyToChargeIfUnconstrained: Double,
    maxDuration: Int,
    remainingDuration: Int
  ) {
    var refueled: Boolean = false
  }

  final case class ChargingVehicle(
    vehicle: BeamVehicle,
    stall: ParkingStall,
    chargingStation: ChargingStation,
    arrivalTime: Int,
    arrivalFuelLevel: Double,
    personId: Id[Person],
    estimatedParkingDuration: Int,
    activityType: String,
    shiftStatus: ShiftStatus,
    shiftDuration: Option[Int],
    theSender: ActorRef,
    chargingStatus: ListBuffer[ChargingStatus] = ListBuffer.empty[ChargingStatus],
    chargingSessions: ListBuffer[ChargingCycle] = ListBuffer.empty[ChargingCycle]
  ) extends LazyLogging {

    import ChargingStatus._

    val chargingShouldEndAt: Option[Int] = shiftDuration.map(_ + arrivalTime)

    val estimatedDepartureTime: Int =
      if (chargingShouldEndAt.isDefined)
        Math.min(arrivalTime + estimatedParkingDuration, chargingShouldEndAt.get)
      else arrivalTime + estimatedParkingDuration

    val chargingCapacityInKw: Double =
      vehicle.beamVehicleType.chargingCapability
        .map(ChargingPointType.getChargingPointInstalledPowerInKw)
        .getOrElse(ChargingPointType.getChargingPointInstalledPowerInKw(stall.chargingPointType.get))

    def isInEnRoute: Boolean = activityType.startsWith(EnRouteLabel)

    def chargingExpectedToEndAt: Int = {
      val chargingEndTime =
        chargingSessions.lastOption.map(cycle => cycle.startTime + cycle.remainingDuration).getOrElse {
          vehicle
            .refuelingSessionDurationAndEnergyInJoules(
              sessionDurationLimit = None,
              stateOfChargeLimit = None,
              chargingPowerLimit = None
            )
            ._1 + arrivalTime
        }
      if (chargingShouldEndAt.isDefined)
        Math.min(chargingShouldEndAt.get, chargingEndTime)
      else chargingEndTime
    }

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
        case Some(cycle @ ChargingCycle(_, _, power, energy, _, _, _)) if !cycle.refueled =>
          vehicle.addFuel(energy)
          cycle.refueled = true
          logger.debug(s"Charging vehicle $vehicle. Power $power kW. Provided energy of = $energy J")
          Some(cycle)
        case _ => None
      }
    }

    /**
      * an unplug request arrived right before the new cycle started
      * or vehicle finished charging right before unplug requests arrived
      *
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
      *
      * @param newCycle Charging Cycle
      * @return boolean value expressing if the charging cycle has been added
      */
    def processCycle(newCycle: ChargingCycle): Option[ChargingCycle] = {
      val addNewChargingCycle = chargingSessions.lastOption match {
        case None =>
          // first charging cycle
          true
        case Some(cycle)
            if newCycle.startTime >= cycle.endTime && chargingStatus.last.status == Connected || (chargingStatus.last.status == Disconnected && chargingStatus.last.time >= newCycle.endTime) =>
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
            newCycle.endTime
          )
          logger.debug(
            "Or the unplug request event for Vehicle {} arrived after it finished charging at time {}",
            vehicle.id,
            newCycle.endTime
          )
          false
      }
      if (addNewChargingCycle) {
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

    override def toString: String = {
      s"$arrivalTime - ${vehicle.id} - ${stall.parkingZoneId} - $personId - $activityType - " +
      s"${chargingStatus.lastOption.getOrElse("None")} - ${chargingSessions.lastOption.getOrElse("None")}"
    }
  }
}
