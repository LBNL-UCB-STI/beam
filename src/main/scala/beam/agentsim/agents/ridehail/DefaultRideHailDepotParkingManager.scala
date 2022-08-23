package beam.agentsim.agents.ridehail

import akka.actor.BeamLoggingReceive
import akka.pattern.pipe
import beam.agentsim.agents.ridehail.DefaultRideHailDepotParkingManager.{
  ChargingQueueEntry,
  ParkingStallsClaimedByVehicles
}
import beam.agentsim.agents.ridehail.RideHailAgent.NotifyVehicleDoneRefuelingAndOutOfServiceReply
import beam.agentsim.agents.ridehail.RideHailManager.{JustArrivedAtDepot, RefuelSource, VehicleId}
import beam.agentsim.agents.ridehail.RideHailManagerHelper.RideHailAgentLocation
import beam.agentsim.agents.vehicles.{BeamVehicle, VehicleManager}
import beam.agentsim.events.{ParkingEvent, SpaceTime}
import beam.agentsim.infrastructure.ChargingNetworkManager._
import beam.agentsim.infrastructure.ParkingInquiry.ParkingSearchMode
import beam.agentsim.infrastructure._
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.TAZ
import beam.agentsim.scheduler.HasTriggerId
import beam.router.BeamRouter.Location
import beam.sim.config.BeamConfig
import beam.utils.logging.LogActorState
import beam.utils.logging.pattern.ask
import org.matsim.api.core.v01.Id

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

trait DefaultRideHailDepotParkingManager extends {
  this: RideHailManager =>

  val outputRidehailParkingFileName = "ridehailParking.csv"
  val rideHailConfig: BeamConfig.Beam.Agentsim.Agents.RideHail = beamServices.beamConfig.beam.agentsim.agents.rideHail
  val depots: Map[Id[ParkingZoneId], ParkingZone] = rideHailChargingNetwork.parkingZones

  /*
   * All internal data to track Depots, ParkingZones, and charging queues are kept in ParkingZoneDepotData which is
   * accessible via a Map on the ParkingZoneId
   */
  protected val parkingZoneIdToParkingZoneDepotData: mutable.Map[Id[ParkingZoneId], ParkingZoneDepotData] =
    mutable.Map.empty[Id[ParkingZoneId], ParkingZoneDepotData]

  depots.foreach { case (parkingZoneId, _) =>
    parkingZoneIdToParkingZoneDepotData.put(parkingZoneId, ParkingZoneDepotData.empty)
  }

  ParkingZoneFileUtils.toCsv(
    rideHailChargingNetwork.parkingZones,
    beamServices.matsimServices.getControlerIO.getOutputFilename(outputRidehailParkingFileName)
  )

  /*
   *  Maps from VehicleId -> XX
   */
  private val chargingVehicleToParkingStallMap: mutable.Map[VehicleId, ParkingStall] =
    mutable.Map.empty[VehicleId, ParkingStall]
  private val vehiclesOnWayToDepot: mutable.Map[VehicleId, ParkingStall] = mutable.Map.empty[VehicleId, ParkingStall]
  private val vehicleIdToEndRefuelTick: mutable.Map[VehicleId, Int] = mutable.Map.empty[VehicleId, Int]

  private val vehiclesInQueueToParkingZoneId: mutable.Map[VehicleId, Id[ParkingZoneId]] =
    mutable.Map.empty[VehicleId, Id[ParkingZoneId]]

  private val vehicleIdToLastObservedTickAndAction: mutable.Map[VehicleId, mutable.ListBuffer[(Int, String)]] =
    mutable.Map.empty[VehicleId, mutable.ListBuffer[(Int, String)]]

  /*
   * Track "Depots" as a mapping from TAZ Id to ParkingZones to facilitate processing all ParkingZones in a depot
   */
  val tazIdToParkingZones: mutable.Map[Id[TAZ], Map[Id[ParkingZoneId], ParkingZone]] =
    mutable.Map.empty[Id[TAZ], Map[Id[ParkingZoneId], ParkingZone]]

  depots.groupBy(_._2.tazId).foreach(tup => tazIdToParkingZones += tup)

  override def loggedReceive: Receive = BeamLoggingReceive {
    case e @ EndingRefuelSession(tick, vehicleId, triggerId) =>
      log.debug("DefaultRideHailDepotParkingManager.EndingRefuelSession: {}", e)
      removingVehicleFromCharging(vehicleId, tick, triggerId)
    case e @ UnpluggingVehicle(tick, personId, vehicle, energyCharged, triggerId) =>
      log.debug("DefaultRideHailDepotParkingManager.UnpluggingVehicle: {}", e)
      ParkingNetworkManager.handleReleasingParkingSpot(
        tick,
        vehicle,
        Some(energyCharged),
        personId,
        parkingManager,
        beamServices.matsimServices.getEvents,
        triggerId
      )
      rideHailManagerHelper.updatePassengerSchedule(vehicle.id, None, None)
      vehicle.getDriver.get ! NotifyVehicleDoneRefuelingAndOutOfServiceReply(triggerId, Vector())
      rideHailManagerHelper.putOutOfService(vehicle.id)
    case e @ UnhandledVehicle(tick, personId, vehicle, triggerId) =>
      log.debug("DefaultRideHailDepotParkingManager.UnhandledVehicle: {}", e)
      ParkingNetworkManager.handleReleasingParkingSpot(
        tick,
        vehicle,
        None,
        personId,
        parkingManager,
        beamServices.matsimServices.getEvents,
        triggerId
      )
      rideHailManagerHelper.updatePassengerSchedule(vehicle.id, None, None)
      vehicle.getDriver.get ! NotifyVehicleDoneRefuelingAndOutOfServiceReply(triggerId, Vector())
      rideHailManagerHelper.putOutOfService(vehicle.id)
    case e @ StartingRefuelSession(tick, vehicleId, stall, _) =>
      log.debug("DefaultRideHailDepotParkingManager.StartingRefuelSession: {}", e)
      addVehicleToChargingInDepotUsing(stall, resources(vehicleId), tick, JustArrivedAtDepot)
    case e @ WaitingToCharge(_, vehicleId, stall, numVehicleWaitingToCharge, _) =>
      log.debug("DefaultRideHailDepotParkingManager.WaitingToCharge: {}", e)
      addVehicleAndStallToRefuelingQueueFor(resources(vehicleId), stall, -numVehicleWaitingToCharge, JustArrivedAtDepot)
  }

  /**
    * @param beamVehicle BeamVehicle
    * @param stall ParkingStall
    * @param tick Time in seconds
    * @param source RefuelSource
    * @param triggerId Long
    */
  def attemptToRefuel(
    beamVehicle: BeamVehicle,
    stall: ParkingStall,
    tick: Int,
    source: RefuelSource,
    triggerId: Long
  ): Unit = {
    eventsManager.processEvent(
      ParkingEvent(tick, stall, beamServices.geo.utm2Wgs(stall.locationUTM), beamVehicle.id, id.toString)
    )
    log.debug("Refuel started at {}, triggerId: {}, vehicle id: {}", tick, triggerId, beamVehicle.id)
    (chargingNetworkManager ? ChargingPlugRequest(
      tick,
      beamVehicle,
      stall,
      Id.createPersonId(id),
      triggerId,
      self
    )).pipeTo(self)
  }

  /**
    * Given a parkingZoneId, dequeue the next vehicle that is waiting to charge.
    *
    * @param parkingZoneId parking zone Id
    * @return optional tuple with [[BeamVehicle]] and [[ParkingStall]]
    */
  def dequeueNextVehicleForRefuelingFrom(parkingZoneId: Id[ParkingZoneId], tick: Int): Option[ChargingQueueEntry] = {
    val chargingQueue = parkingZoneIdToParkingZoneDepotData(parkingZoneId).chargingQueue
    if (chargingQueue.isEmpty) {
      None
    } else {
      val ChargingQueueEntry(beamVehicle, parkingStall, _) = chargingQueue.dequeue
      log.debug("Dequeueing vehicle {} to charge at depot {}", beamVehicle, parkingStall.parkingZoneId)
      putNewTickAndObservation(beamVehicle.id, (tick, "DequeueToCharge"))
      vehiclesInQueueToParkingZoneId.remove(beamVehicle.id)
      Some(ChargingQueueEntry(beamVehicle, parkingStall, 1.0))
    }
  }

  /**
    * Adds a vehicle to internal data structures to track that it is engaged in a charging session.
    *
    * @param stall ParkingStall
    * @param beamVehicle BeamVehicle
    * @param tick time in seconds
    * @param source Tag used for logging purposes.
    */
  def addVehicleToChargingInDepotUsing(
    stall: ParkingStall,
    beamVehicle: BeamVehicle,
    tick: Int,
    source: RefuelSource
  ): Boolean = {
    if (chargingVehicleToParkingStallMap.keys.exists(_ == beamVehicle.id)) {
      log.warning(
        "{} is already charging in {}, yet it is being added to {}. Source: {} THIS SHOULD NOT HAPPEN!",
        beamVehicle.id,
        chargingVehicleToParkingStallMap(beamVehicle.id),
        stall,
        source
      )
      beamVehicle.getDriver.get ! LogActorState
      false
    } else {
      log.debug(
        "Cache that vehicle {} is now charging in depot {}, source {}",
        beamVehicle.id,
        stall.parkingZoneId,
        source
      )
      chargingVehicleToParkingStallMap += beamVehicle.id -> stall
      parkingZoneIdToParkingZoneDepotData(stall.parkingZoneId).chargingVehicles.add(beamVehicle.id)
      val (chargingSessionDuration, _) = beamVehicle.refuelingSessionDurationAndEnergyInJoules(None, None, None)
      putNewTickAndObservation(beamVehicle.id, (tick, s"Charging($source)"))
      vehicleIdToEndRefuelTick.put(beamVehicle.id, tick + chargingSessionDuration)
      true
    }
  }

  /**
    * Store the last tick and action observed by this vehicle. For debugging purposes.
    *
    * @param vehicleId VehicleId
    * @param tickAndAction a tuple with the tick and action label (String) to store
    * @return
    */
  def putNewTickAndObservation(vehicleId: VehicleId, tickAndAction: (Int, String)): Any = {
    vehicleIdToLastObservedTickAndAction.get(vehicleId) match {
      case Some(listBuffer) =>
        listBuffer.append(tickAndAction)
      case None =>
        val listBuffer = new ListBuffer[(Int, String)]()
        listBuffer.append(tickAndAction)
        vehicleIdToLastObservedTickAndAction.put(vehicleId, listBuffer)
    }
  }

  /**
    * This vehicle is no longer charging and should be removed from internal tracking data.
    *
    * @param vehicleId Beam Vehicle ID
    * @param tick time in seconds
    * @param triggerId Long
    * @return the stall if found and successfully removed
    */
  def removeFromCharging(vehicleId: VehicleId, tick: Int, triggerId: Long): Option[ParkingStall] = {
    vehicleIdToEndRefuelTick.remove(vehicleId)
    chargingVehicleToParkingStallMap.remove(vehicleId) match {
      case Some(stall) =>
        log.debug("Remove from cache that vehicle {} was charging in stall {}", vehicleId, stall)
        putNewTickAndObservation(vehicleId, (tick, "RemoveFromCharging"))
        parkingZoneIdToParkingZoneDepotData(stall.parkingZoneId).chargingVehicles.remove(vehicleId)
        releaseStall(stall, tick, resources(vehicleId), triggerId)
        Some(stall)
      case _ =>
        None
    }
  }

  /**
    * releases a single stall in use at this Depot
    *
    * @param parkingStall stall we want to release
    * @return Boolean if zone is defined and remove was success
    */
  private def releaseStall(parkingStall: ParkingStall, tick: Int, beamVehicle: BeamVehicle, triggerId: Long): Unit = {
    if (parkingStall.chargingPointType.isEmpty) {
      ParkingNetworkManager.handleReleasingParkingSpot(
        tick,
        beamVehicle,
        None,
        this.id,
        parkingManager,
        beamServices.matsimServices.getEvents,
        triggerId
      )
    } else {
      val request = ChargingUnplugRequest(tick, this.id, beamVehicle, triggerId)
      (chargingNetworkManager ? request).pipeTo(self)
    }
  }

  /**
    * Adds the vehicle to the appropriate queue for the depot
    *
    * @param vehicle BeamVehicle
    * @param stall ParkingStall
    * @param source used for logging purposes only.
    */
  def addVehicleAndStallToRefuelingQueueFor(
    vehicle: BeamVehicle,
    stall: ParkingStall,
    priority: Double,
    source: RefuelSource
  ): Unit = {
    val chargingQueue = parkingZoneIdToParkingZoneDepotData(stall.parkingZoneId).chargingQueue
    val chargingQueueEntry = ChargingQueueEntry(vehicle, stall, priority)
    if (chargingQueue.exists(_.beamVehicle.id == vehicle.id)) {
      log.warning(
        "{} already exists in parking zone {} queue. Not re-adding as it is a duplicate. Source: {} " +
        "THIS SHOULD NEVER HAPPEN!",
        vehicle.id,
        stall.parkingZoneId,
        source
      )
    } else {
      log.debug(
        "Add vehicle {} to charging queue of length {} at depot {}",
        vehicle.id,
        chargingQueue.size,
        stall.parkingZoneId
      )
      putNewTickAndObservation(vehicle.id, (vehicle.spaceTime.time, s"EnQueue($source)"))
      vehiclesInQueueToParkingZoneId.put(vehicle.id, stall.parkingZoneId)
      chargingQueue.enqueue(chargingQueueEntry)
    }
  }

  /**
    * Notify this [[RideHailDepotParkingManager]] that vehicles are on the way to the depot for the purpose of refueling.
    *
    * @param newVehiclesHeadedToDepot vector of tuple of vehicle Id and parking stall
    */
  def notifyVehiclesOnWayToRefuelingDepot(newVehiclesHeadedToDepot: Vector[(VehicleId, ParkingStall)]): Unit = {
    newVehiclesHeadedToDepot.foreach { case (vehicleId, parkingStall) =>
      log.debug("Vehicle {} headed to depot depot {}", vehicleId, parkingStall.parkingZoneId)
      vehiclesOnWayToDepot.put(vehicleId, parkingStall)
      val parkingZoneDepotData = parkingZoneIdToParkingZoneDepotData(parkingStall.parkingZoneId)
      parkingZoneDepotData.vehiclesOnWayToDepot.add(vehicleId)
    }
  }

  /**
    * Is the [[VehicleId]] currently on the way to a refueling depot to charge?
    *
    * @param vehicleId Beam Vehicle ID
    * @return
    */
  def isOnWayToRefuelingDepot(vehicleId: VehicleId): Boolean = vehiclesOnWayToDepot.contains(vehicleId)

  /**
    * Is the [[VehicleId]] currently on the way to a refueling depot to charge or actively charging?
    *
    * @param vehicleId VehicleId
    * @return
    */
  def isOnWayToRefuelingDepotOrIsRefuelingOrInQueue(vehicleId: VehicleId): Boolean =
    vehiclesOnWayToDepot.contains(vehicleId) || chargingVehicleToParkingStallMap.contains(
      vehicleId
    ) || vehiclesInQueueToParkingZoneId
      .contains(vehicleId)

  /**
    * Notify this [[RideHailDepotParkingManager]] that a vehicles is no longer on the way to the depot.
    *
    * @param vehicleId VehicleId
    * @return the optional [[ParkingStall]] of the vehicle if it was found in the internal tracking, None if
    *         the vehicle was not found.
    */
  def notifyVehicleNoLongerOnWayToRefuelingDepot(vehicleId: VehicleId): Option[ParkingStall] = {
    val parkingStallOpt = vehiclesOnWayToDepot.remove(vehicleId)
    parkingStallOpt match {
      case Some(parkingStall) =>
        parkingZoneIdToParkingZoneDepotData(parkingStall.parkingZoneId).vehiclesOnWayToDepot.remove(vehicleId)
      case None =>
      // still charging
    }
    parkingStallOpt
  }

  /**
    * *
    * @param tick time in second
    * @param vehiclesWithoutCustomVehicles Map of BeamVehicle and RideHailAgentLocation
    * @param additionalCustomVehiclesForDepotCharging Vector or tuple of BeamVehicle Id and parking stall
    * @param triggerId Long
    * @return
    */
  def findChargingStalls(
    tick: Int,
    vehiclesWithoutCustomVehicles: Map[Id[BeamVehicle], RideHailAgentLocation],
    additionalCustomVehiclesForDepotCharging: Vector[(Id[BeamVehicle], ParkingStall)],
    triggerId: Long
  ): Unit = {
    val idleVehicleIdsWantingToRefuelWithLocation = vehiclesWithoutCustomVehicles.toVector.filter {
      case (vehicleId: Id[BeamVehicle], _) =>
        resources.get(vehicleId) match {
          case Some(beamVehicle) if beamVehicle.isCAV =>
            beamVehicle.isRefuelNeeded(
              rideHailConfig.cav.refuelRequiredThresholdInMeters,
              rideHailConfig.cav.noRefuelThresholdInMeters
            )
          case _ => false
        }
    }
    Future
      .sequence(idleVehicleIdsWantingToRefuelWithLocation.map { case (vehicleId, rideHailAgentLocation) =>
        val beamVehicle = resources(vehicleId)
        val locationUtm: Location = rideHailAgentLocation.getCurrentLocationUTM(tick, beamServices)
        sendChargingInquiry(SpaceTime(locationUtm, tick), beamVehicle, triggerId)
          .map { response =>
            beamVehicle.setReservedParkingStall(Some(response.stall))
            (vehicleId, response.stall)
          }
      })
      .foreach { result =>
        self ! ParkingStallsClaimedByVehicles(tick, result, additionalCustomVehiclesForDepotCharging, triggerId)
      }
  }

  /**
    * *
    * @param whenWhere SpaceTime
    * @param beamVehicle BeamVehicle
    * @param triggerId Long
    * @return
    */
  def sendChargingInquiry(
    whenWhere: SpaceTime,
    beamVehicle: BeamVehicle,
    triggerId: Long
  ): Future[ParkingInquiryResponse] = {
    val inquiry = ParkingInquiry.init(
      whenWhere,
      "wherever",
      VehicleManager.getReservedFor(beamVehicle.vehicleManagerId.get).get,
      Some(beamVehicle),
      valueOfTime = rideHailConfig.cav.valueOfTime,
      reserveStall = false,
      searchMode = ParkingSearchMode.DestinationCharging,
      triggerId = triggerId
    )
    (chargingNetworkManager ? inquiry).mapTo[ParkingInquiryResponse]
  }
}

object DefaultRideHailDepotParkingManager {

  case class ParkingStallsClaimedByVehicles(
    tick: Int,
    responses: Vector[(Id[BeamVehicle], ParkingStall)],
    // TODO for the future consider migrating this custom vehicles for the API to the chargingNetworkManager
    additionalCustomVehiclesForDepotCharging: Vector[(Id[BeamVehicle], ParkingStall)],
    triggerId: Long
  ) extends HasTriggerId

  case class ChargingQueueEntry(beamVehicle: BeamVehicle, parkingStall: ParkingStall, priority: Double)
}
