package beam.agentsim.agents.ridehail

import akka.actor.BeamLoggingReceive
import akka.pattern.pipe
import beam.agentsim.agents.ridehail.RideHailDepotManager.{
  DequeuedToCharge,
  JustArrivedAtDepot,
  ParkingStallsClaimedByVehicles,
  ParkingZoneDepotData
}
import beam.agentsim.agents.ridehail.RideHailManager.VehicleId
import beam.agentsim.agents.ridehail.RideHailManagerHelper.RideHailAgentLocation
import beam.agentsim.agents.vehicles.{BeamVehicle, VehicleManager}
import beam.agentsim.events.{ParkingEvent, SpaceTime}
import beam.agentsim.infrastructure.ChargingNetworkManager._
import beam.agentsim.infrastructure.ParkingInquiry.ParkingSearchMode
import beam.agentsim.infrastructure._
import beam.agentsim.infrastructure.parking._
import beam.agentsim.scheduler.HasTriggerId
import beam.router.BeamRouter.Location
import beam.sim.config.BeamConfig
import beam.utils.logging.pattern.ask
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

trait RideHailDepotManager extends {
  this: RideHailManager =>

  val outputRideHailParkingFileName = "ridehailParking.csv"
  val rideHailConfig: BeamConfig.Beam.Agentsim.Agents.RideHail = beamServices.beamConfig.beam.agentsim.agents.rideHail

  /*
   * All internal data to track Depots, ParkingZones, and charging queues are kept in ParkingZoneDepotData which is
   * accessible via a Map on the ParkingZoneId
   */
  private val parkingZoneIdToParkingZoneDepotData: mutable.Map[Id[ParkingZoneId], ParkingZoneDepotData] =
    mutable.Map.empty[Id[ParkingZoneId], ParkingZoneDepotData]

  ParkingZoneFileUtils.toCsv(
    rideHailChargingNetwork.parkingZones,
    beamServices.matsimServices.getControlerIO.getOutputFilename(outputRideHailParkingFileName)
  )

  /*
   *  Maps from VehicleId -> XX
   */
  private val vehicleIdToLastObservedTickAndAction: mutable.Map[VehicleId, mutable.ListBuffer[(Int, String)]] =
    mutable.Map.empty[VehicleId, mutable.ListBuffer[(Int, String)]]

  override def loggedReceive: Receive = BeamLoggingReceive {
    case e: EndingRefuelSession =>
      log.info("RideHailDepotManager.EndingRefuelSession: {}", e)
    case e @ UnpluggingVehicle(_, _, vehicle, stall, _) =>
      log.info("RideHailDepotManager.UnpluggingVehicle: {}", e)
      val depotData = getParkingZoneIdToParkingZoneDepotData(stall.parkingZoneId)
      depotData.vehiclesInQueue.remove(vehicle.id)
      depotData.vehiclesOnWayToDepot.remove(vehicle.id)
      depotData.chargingVehicles.remove(vehicle.id)
    case e @ UnhandledVehicle(_, _, vehicle, stallMaybe) =>
      log.info("RideHailDepotManager.UnhandledVehicle: {}", e)
      stallMaybe.map(stall => getParkingZoneIdToParkingZoneDepotData(stall.parkingZoneId)).map { depotData =>
        depotData.vehiclesInQueue.remove(vehicle.id)
        depotData.vehiclesOnWayToDepot.remove(vehicle.id)
        depotData.chargingVehicles.remove(vehicle.id)
      }
    case e @ StartingRefuelSession(tick, vehicleId, stall, _) =>
      log.info("RideHailDepotManager.StartingRefuelSession: {}", e)
      val depotData = getParkingZoneIdToParkingZoneDepotData(stall.parkingZoneId)
      if (depotData.vehiclesInQueue.remove(vehicleId).isDefined) {
        putNewTickAndObservation(vehicleId, (tick, s"EnQueue($DequeuedToCharge)"))
      }
      depotData.vehiclesOnWayToDepot.remove(vehicleId)
      depotData.chargingVehicles.put(vehicleId, stall)
    case e @ WaitingToCharge(_, vehicleId, stall, _) =>
      log.info("RideHailDepotManager.WaitingToCharge: {}", e)
      val depotData = getParkingZoneIdToParkingZoneDepotData(stall.parkingZoneId)
      depotData.vehiclesOnWayToDepot.remove(vehicleId)
      depotData.vehiclesInQueue.put(vehicleId, stall)
  }

  /**
    * @param beamVehicle BeamVehicle
    * @param stall ParkingStall
    * @param tick Time in seconds
    * @param triggerId Long
    */
  def attemptToRefuel(
    beamVehicle: BeamVehicle,
    personId: Id[Person],
    stall: ParkingStall,
    tick: Int,
    triggerId: Long
  ): Unit = {
    eventsManager.processEvent(
      ParkingEvent(tick, stall, beamServices.geo.utm2Wgs(stall.locationUTM), beamVehicle.id, id.toString)
    )
    putNewTickAndObservation(beamVehicle.id, (tick, s"Charging($JustArrivedAtDepot)"))
    (chargingNetworkManager ? ChargingPlugRequest(
      tick,
      beamVehicle,
      stall,
      personId,
      triggerId,
      beamVehicle.getDriver.get
    )).pipeTo(beamVehicle.getDriver.get)
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
    parkingZoneIdToParkingZoneDepotData
      .filter(_._2.chargingVehicles.contains(vehicleId))
      .flatMap(_._2.chargingVehicles.get(vehicleId))
      .headOption
      .map { stall =>
        log.debug("Remove from cache that vehicle {} was charging in stall {}", vehicleId, stall)
        putNewTickAndObservation(vehicleId, (tick, "RemoveFromCharging"))
        getParkingZoneIdToParkingZoneDepotData(stall.parkingZoneId).chargingVehicles.remove(vehicleId)
        releaseStall(stall, tick, resources(vehicleId), triggerId)
        stall
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
        beamServices.matsimServices.getEvents
      )
    } else {
      (chargingNetworkManager ? ChargingUnplugRequest(tick, this.id, beamVehicle, triggerId))
        .pipeTo(beamVehicle.getDriver.get)
    }
  }

  /**
    * Notify this [[RideHailDepotManager]] that vehicles are on the way to the depot for the purpose of refueling.
    *
    * @param newVehiclesHeadedToDepot vector of tuple of vehicle Id and parking stall
    */
  def notifyVehiclesOnWayToRefuelingDepot(newVehiclesHeadedToDepot: Vector[(VehicleId, ParkingStall)]): Unit = {
    newVehiclesHeadedToDepot.foreach { case (vehicleId, parkingStall) =>
      log.debug("Vehicle {} headed to depot depot {}", vehicleId, parkingStall.parkingZoneId)
      getParkingZoneIdToParkingZoneDepotData(parkingStall.parkingZoneId).vehiclesOnWayToDepot
        .put(vehicleId, Some(parkingStall))
    }
  }

  /**
    * Is the [[VehicleId]] currently on the way to a refueling depot to charge?
    *
    * @param vehicleId Beam Vehicle ID
    * @return
    */
  def isOnWayToRefuelingDepot(vehicleId: VehicleId): Boolean =
    parkingZoneIdToParkingZoneDepotData.exists(_._2.vehiclesOnWayToDepot.contains(vehicleId))

  /**
    * Is the [[VehicleId]] currently on the way to a refueling depot to charge or actively charging?
    *
    * @param vehicleId VehicleId
    * @return
    */
  def isOnWayToRefuelingDepotOrIsRefuelingOrInQueue(vehicleId: VehicleId): Boolean =
    parkingZoneIdToParkingZoneDepotData.exists { case (_, depotData) =>
      depotData.vehiclesInQueue.contains(vehicleId) || depotData.vehiclesOnWayToDepot.contains(vehicleId) ||
        depotData.chargingVehicles.contains(vehicleId)
    }

  /**
    * Notify this [[RideHailDepotManager]] that a vehicles is no longer on the way to the depot.
    *
    * @param vehicleId VehicleId
    * @return the optional [[ParkingStall]] of the vehicle if it was found in the internal tracking, None if
    *         the vehicle was not found.
    */
  def notifyVehicleNoLongerOnWayToRefuelingDepot(vehicleId: VehicleId): Option[ParkingStall] = {
    parkingZoneIdToParkingZoneDepotData.flatMap(_._2.vehiclesOnWayToDepot.remove(vehicleId)).headOption.flatMap {
      case Some(parkingStall) => Some(parkingStall)
      case None               => None
    }
  }

  private def getParkingZoneIdToParkingZoneDepotData(parkingZoneId: Id[ParkingZoneId]): ParkingZoneDepotData = {
    parkingZoneIdToParkingZoneDepotData.get(parkingZoneId) match {
      case None    => parkingZoneIdToParkingZoneDepotData.put(parkingZoneId, ParkingZoneDepotData.empty)
      case Some(_) =>
    }
    parkingZoneIdToParkingZoneDepotData(parkingZoneId)
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
    log.info(
      s"findChargingStalls tick $tick idleVehicleIdsWantingToRefuelWithLocation ${idleVehicleIdsWantingToRefuelWithLocation.size}"
    )
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
        self ! ParkingStallsClaimedByVehicles(
          tick,
          result.filter(_._2.chargingPointType.isDefined),
          additionalCustomVehiclesForDepotCharging,
          triggerId
        )
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

object RideHailDepotManager {

  sealed trait RefuelSource
  case object JustArrivedAtDepot extends RefuelSource
  case object DequeuedToCharge extends RefuelSource

  case class ParkingZoneDepotData(
    vehiclesOnWayToDepot: mutable.HashMap[VehicleId, Option[ParkingStall]],
    chargingVehicles: mutable.HashMap[VehicleId, ParkingStall],
    vehiclesInQueue: mutable.HashMap[VehicleId, ParkingStall]
  )

  object ParkingZoneDepotData {

    def empty: ParkingZoneDepotData =
      ParkingZoneDepotData(
        mutable.HashMap.empty[VehicleId, Option[ParkingStall]],
        mutable.HashMap.empty[VehicleId, ParkingStall],
        mutable.HashMap.empty[VehicleId, ParkingStall]
      )
  }

  case class ParkingStallsClaimedByVehicles(
    tick: Int,
    responses: Vector[(Id[BeamVehicle], ParkingStall)],
    // TODO for the future consider migrating this custom vehicles for the API to the chargingNetworkManager
    additionalCustomVehiclesForDepotCharging: Vector[(Id[BeamVehicle], ParkingStall)],
    triggerId: Long
  ) extends HasTriggerId
}
