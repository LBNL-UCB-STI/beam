package beam.agentsim.agents.ridehail

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.PriorityQueue
import scala.collection.parallel.{ParIterable, ParSeq}

import akka.actor.ActorRef
import beam.agentsim.agents.ridehail.RideHailManagerHelper._
import beam.agentsim.agents.ridehail.RideHailMatching.VehicleAndSchedule
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, PassengerSchedule}
import beam.agentsim.agents.vehicles.BeamVehicle.BeamVehicleState
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.BeamRouter
import beam.router.BeamRouter.Location
import beam.router.Modes.BeamMode.CAR
import beam.sim.{BeamServices, Geofence}
import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree

object RideHailAgentETAComparatorMinTimeToCustomer extends Ordering[RideHailAgentETA] {
  override def compare(
    o1: RideHailAgentETA,
    o2: RideHailAgentETA
  ): Int = {
    java.lang.Double.compare(o1.timeToCustomer, o2.timeToCustomer)
  }
}

object RideHailAgentETAComparatorServiceTime extends Ordering[RideHailAgentETA] {
  override def compare(
    o1: RideHailAgentETA,
    o2: RideHailAgentETA
  ): Int = {
    java.lang.Double.compare(o1.totalServiceTime, o2.totalServiceTime)
  }
}

object RideHailAgentLocationWithRadiusOrdering extends Ordering[(RideHailAgentLocation, Double)] {
  override def compare(
    o1: (RideHailAgentLocation, Double),
    o2: (RideHailAgentLocation, Double)
  ): Int = {
    java.lang.Double.compare(o1._2, o2._2)
  }
}

/**
  * BEAM
  */
class RideHailManagerHelper(rideHailManager: RideHailManager, boundingBox: Envelope) extends LazyLogging {

  val vehicleState: mutable.Map[Id[BeamVehicle], BeamVehicleState] =
    mutable.Map[Id[BeamVehicle], BeamVehicleState]()

  private[ridehail] val idleRideHailAgentSpatialIndex: QuadTree[RideHailAgentLocation] = {
    new QuadTree[RideHailAgentLocation](
      boundingBox.getMinX,
      boundingBox.getMinY,
      boundingBox.getMaxX,
      boundingBox.getMaxY
    )
  }

  private val inServiceRideHailAgentSpatialIndex: QuadTree[RideHailAgentLocation] = {
    new QuadTree[RideHailAgentLocation](
      boundingBox.getMinX,
      boundingBox.getMinY,
      boundingBox.getMaxX,
      boundingBox.getMaxY
    )
  }

  private val outOfServiceRideHailAgentSpatialIndex: QuadTree[RideHailAgentLocation] = {
    new QuadTree[RideHailAgentLocation](
      boundingBox.getMinX,
      boundingBox.getMinY,
      boundingBox.getMaxX,
      boundingBox.getMaxY
    )
  }

  private val refuelingRideHailAgentSpatialIndex = {
    new QuadTree[RideHailAgentLocation](
      boundingBox.getMinX,
      boundingBox.getMinY,
      boundingBox.getMaxX,
      boundingBox.getMaxY
    )
  }

  private[ridehail] val idleRideHailVehicles = mutable.HashMap[Id[BeamVehicle], RideHailAgentLocation]()
  private[ridehail] val outOfServiceRideHailVehicles = mutable.HashMap[Id[BeamVehicle], RideHailAgentLocation]()
  private[ridehail] val inServiceRideHailVehicles = mutable.HashMap[Id[BeamVehicle], RideHailAgentLocation]()
  private val refuelingRideHailVehicles = mutable.HashMap[Id[BeamVehicle], RideHailAgentLocation]()
  private val vehicleOutOfCharge = mutable.Set[Id[BeamVehicle]]()
  private val mobileVehicleChargingTimes = PriorityQueue[(Int, Id[BeamVehicle])]()(Ordering.by(-_._1))
  private[this] var latestSpatialIndexUpdateTick = 0

  def addVehicleOutOfCharge(vehicleId: Id[BeamVehicle]): Boolean = {
    vehicleOutOfCharge.add(vehicleId)
  }

  def modelMobileRufuelChargingDelay(time: Int, vehicleId: Id[BeamVehicle]) = {
    mobileVehicleChargingTimes.enqueue((time, vehicleId))
  }

  def getMobileChargedVehiclesForProcessing(time: Int): mutable.Set[Id[BeamVehicle]] = {
    val result = mutable.Set[Id[BeamVehicle]]()
    while (!mobileVehicleChargingTimes.isEmpty && time > mobileVehicleChargingTimes.head._1) {
      val (endChargingTime, vehicleId) = mobileVehicleChargingTimes.dequeue()
      vehicleOutOfCharge.remove(vehicleId)
      result.add(vehicleId)
    }
    result
  }

  def getVehicleState(vehicleId: Id[BeamVehicle]): BeamVehicleState =
    vehicleState(vehicleId)

  def getRideHailAgentLocation(vehicleId: Id[BeamVehicle]): RideHailAgentLocation = {
    getServiceStatusOf(vehicleId) match {
      case Available =>
        idleRideHailVehicles(vehicleId)
      case InService =>
        inServiceRideHailVehicles(vehicleId)
      case Refueling =>
        refuelingRideHailVehicles(vehicleId)
      case OutOfService =>
        outOfServiceRideHailVehicles.get(vehicleId) match {
          case Some(agentLocation) =>
            agentLocation
          case None =>
            logger.error(s"Unknown service status of RideHailAgent $vehicleId, creating a default Location")
            val beamVehicle = rideHailManager.resources(vehicleId)
            val location = if (beamVehicle.spaceTime == null) {
              //TODO this should ideally pull the location of the specific RHA
              SpaceTime(
                rideHailManager.rideHailFleetInitializer
                  .getRideHailAgentInitializers(rideHailManager.id, rideHailManager.activityQuadTreeBounds)
                  .find(_.id.equalsIgnoreCase(vehicleId.toString))
                  .map(_.initialLocation)
                  .getOrElse(
                    rideHailManager.rideHailFleetInitializer
                      .getRideHailAgentInitializers(rideHailManager.id, rideHailManager.activityQuadTreeBounds)
                      .head
                      .initialLocation
                  ),
                0
              )
            } else { beamVehicle.spaceTime }
            RideHailAgentLocation(beamVehicle.getDriver.get, vehicleId, beamVehicle.beamVehicleType, location)
        }
    }
  }

  def getClosestIdleVehiclesWithinRadiusByETA(
    pickupLocation: Coord,
    dropOffLocation: Coord,
    radius: Double,
    customerRequestTime: Int,
    maxWaitingTimeInSec: Double,
    excludeRideHailVehicles: Set[Id[BeamVehicle]] = Set(),
    includeRepositioningVehicles: Boolean = false
  ): Option[RideHailAgentETA] = {
    var start = System.currentTimeMillis()
    val nearbyAvailableRideHailAgents: ParIterable[RideHailAgentLocation] = {
      val agentsInRadius = selectAgentsInRadius(pickupLocation, radius, includeRepositioningVehicles)
      val searchOnlyVehicles = selectVehiclesToSearchOn(excludeRideHailVehicles, includeRepositioningVehicles)
      filterRideHailAgentsFromVehicles(pickupLocation, dropOffLocation, agentsInRadius, searchOnlyVehicles)
    }
    var end = System.currentTimeMillis()
    val diff1 = end - start

    start = System.currentTimeMillis()
    val times2RideHailAgents = calculateTimesToRideHailAgents(
      pickupLocation = pickupLocation,
      dropOffLocation = dropOffLocation,
      customerRequestTime = customerRequestTime,
      maxWaitingTimeInSec = maxWaitingTimeInSec,
      nearbyAvailableRideHailAgents = nearbyAvailableRideHailAgents
    )
    end = System.currentTimeMillis()
    val diff2 = end - start

    if (diff1 + diff2 > 100)
      logger.debug(
        s"getClosestIdleVehiclesWithinRadiusByETA for $pickupLocation with $radius nearbyAvailableRideHailAgents: $diff1, diff2: $diff2. Total: ${diff1 + diff2} ms"
      )

    if (times2RideHailAgents.isEmpty) None
    else {
      Some(times2RideHailAgents.min(RideHailAgentETAComparatorServiceTime))
    }
  }

  def calculateTimesToRideHailAgents(
    pickupLocation: Location,
    dropOffLocation: Location,
    customerRequestTime: Int,
    maxWaitingTimeInSec: Double,
    nearbyAvailableRideHailAgents: ParIterable[RideHailAgentLocation]
  ): ParIterable[RideHailAgentETA] = {
    val pickupTazId: Id[TAZ] = rideHailManager.beamScenario.tazTreeMap
      .getTAZ(pickupLocation.getX, pickupLocation.getY)
      .tazId
    val dropOffTazId: Id[TAZ] = rideHailManager.beamScenario.tazTreeMap
      .getTAZ(dropOffLocation.getX, dropOffLocation.getY)
      .tazId
    val allAgentsETA = nearbyAvailableRideHailAgents.map { rideHailAgentLocation =>
      calculateAgentETA(
        pickupLocation,
        dropOffLocation,
        customerRequestTime,
        pickupTazId,
        dropOffTazId,
        rideHailAgentLocation
      )
    }
    val bufferForDispatchInMeters: Int =
      rideHailManager.beamServices.beamConfig.beam.agentsim.agents.rideHail.rangeBufferForDispatchInMeters
    filterETALessThanMaxWaitingTimeAndWithEnoughFuel(
      maxWaitingTimeInSec,
      allAgentsETA,
      rideHailManager.resources,
      bufferForDispatchInMeters
    )
  }

  private def calculateAgentETA(
    pickupLocation: Location,
    dropOffLocation: Location,
    customerRequestTime: Int,
    pickupTazId: Id[TAZ],
    dropOffTazId: Id[TAZ],
    rideHailAgentLocation: RideHailAgentLocation
  ): RideHailAgentETA = {
    val fuelPrice = rideHailManager.beamScenario.fuelTypePrices(rideHailAgentLocation.vehicleType.primaryFuelType)
    val skimTimeAndDistanceToCustomer = BeamRouter.computeTravelTimeAndDistanceAndCost(
      originUTM = rideHailAgentLocation.getCurrentLocationUTM(customerRequestTime, rideHailManager.beamServices),
      destinationUTM = pickupLocation,
      departureTime = customerRequestTime,
      mode = CAR,
      vehicleTypeId = rideHailAgentLocation.vehicleType.id,
      rideHailAgentLocation.vehicleType,
      fuelPrice,
      beamScenario = rideHailManager.beamScenario,
      skimmer = rideHailManager.beamServices.skims.od_skimmer,
      maybeOrigTazId = None,
      maybeDestTazId = Some(pickupTazId),
    )
    val skimTimeAndDistanceOfTrip = BeamRouter.computeTravelTimeAndDistanceAndCost(
      originUTM = pickupLocation,
      destinationUTM = dropOffLocation,
      departureTime = customerRequestTime,
      mode = CAR,
      vehicleTypeId = rideHailAgentLocation.vehicleType.id,
      rideHailAgentLocation.vehicleType,
      fuelPrice,
      beamScenario = rideHailManager.beamScenario,
      skimmer = rideHailManager.beamServices.skims.od_skimmer,
      maybeOrigTazId = Some(pickupTazId),
      maybeDestTazId = Some(dropOffTazId),
    )
    // we consider the time to travel to the customer and the time before the vehicle is actually ready (due to
    // already moving or dropping off a customer, etc.)
    val extra = Math.max(rideHailAgentLocation.latestUpdatedLocationUTM.time - customerRequestTime, 0)
    val totalWaitTime = skimTimeAndDistanceToCustomer.time + extra
    val totalServiceTime = totalWaitTime + skimTimeAndDistanceOfTrip.time
    val totalServiceDistance = skimTimeAndDistanceToCustomer.distance + skimTimeAndDistanceOfTrip.distance
    RideHailAgentETA(rideHailAgentLocation, totalServiceDistance, totalWaitTime, totalServiceTime)
  }

  private def selectVehiclesToSearchOn(
    excludeRideHailVehicles: Set[Id[BeamVehicle]],
    includeRepositioningVehicles: Boolean
  ): Set[Id[BeamVehicle]] = {
    val filteredIdleVehicles: Set[Id[BeamVehicle]] = if (includeRepositioningVehicles) {
      getIdleAndRepositioningVehiclesAndFilterOutExluded.keySet.toSet
    } else {
      getIdleVehiclesAndFilterOutExluded.keySet.toSet
    }
    filteredIdleVehicles -- excludeRideHailVehicles -- vehicleOutOfCharge
  }

  private def selectAgentsInRadius(
    pickupLocation: Location,
    radius: Double,
    includeRepositioningVehicles: Boolean
  ): ParSeq[RideHailAgentLocation] = {
    val newSource =
      if (includeRepositioningVehicles)
        Seq(idleRideHailAgentSpatialIndex, inServiceRideHailAgentSpatialIndex)
      else
        Seq(idleRideHailAgentSpatialIndex)
    RideHailManagerHelper.selectAgentsInRadius(pickupLocation, radius, newSource.par)
  }

  private[ridehail] def filterRideHailAgentsFromVehicles(
    pickupLocation: Location,
    dropoffLocation: Location,
    nearbyRideHailAgents: ParIterable[RideHailAgentLocation],
    searchOnlyVehicles: Set[Id[BeamVehicle]]
  ): ParIterable[RideHailAgentLocation] = {
    nearbyRideHailAgents
      .filter { x =>
        searchOnlyVehicles.contains(x.vehicleId) &&
        (x.geofence.isEmpty || ((x.geofence.isDefined && x.geofence.get.contains(pickupLocation)) &&
        (x.geofence.isDefined && x.geofence.get
          .contains(dropoffLocation))))
      }
  }

  /**
    * Returns a map of ride hail vehicles that are either idle or in-service but repositioning and then filters out
    * any that should be excluded according to the doNotUseInAllocation map.
    * @return HashMap from Id[BeamVehicle] to RideHailAgentLocation
    */
  def getIdleAndRepositioningVehiclesAndFilterOutExluded: mutable.Map[Id[BeamVehicle], RideHailAgentLocation] = {
    val repositioningVehicles = getRepositioningVehicles
    val maxSize = idleRideHailVehicles.size + repositioningVehicles.size
    val filteredVehicles = new java.util.HashMap[Id[BeamVehicle], RideHailAgentLocation](maxSize)

    def addIfNotInAllocation(
      idleOrRepositioning: mutable.HashMap[Id[BeamVehicle], RideHailManagerHelper.RideHailAgentLocation]
    ): Unit = {
      idleOrRepositioning.foreach {
        case (vehicleId, location) =>
          if (!rideHailManager.doNotUseInAllocation.contains(vehicleId)) {
            filteredVehicles.put(vehicleId, location)
          }
      }
    }

    addIfNotInAllocation(idleRideHailVehicles)
    addIfNotInAllocation(repositioningVehicles)

    filteredVehicles.asScala
  }

  def getIdleAndRepositioningAndOfflineCAVsAndFilterOutExluded
    : mutable.HashMap[Id[BeamVehicle], RideHailAgentLocation] = {
    collection.mutable.HashMap(
      (idleRideHailVehicles.toMap ++ inServiceRideHailVehicles
        .filter(_._2.currentPassengerSchedule.exists(_.numUniquePassengers == 0))
        .toMap ++ outOfServiceRideHailVehicles.filter(_._2.vehicleType.automationLevel >= 4).toMap)
        .filterNot(elem => rideHailManager.doNotUseInAllocation.contains(elem._1))
        .toSeq: _*
    )
  }

  def getIdleVehicles: mutable.HashMap[Id[BeamVehicle], RideHailAgentLocation] = {
    idleRideHailVehicles
  }

  def getRepositioningVehicles: mutable.HashMap[Id[BeamVehicle], RideHailAgentLocation] = {
    inServiceRideHailVehicles.par
      .filter(_._2.currentPassengerSchedule.exists(_.numUniquePassengers == 0))
      .seq
  }

  def getVehiclesServingCustomers: mutable.HashMap[Id[BeamVehicle], RideHailAgentLocation] = {
    inServiceRideHailVehicles.filter(_._2.currentPassengerSchedule.exists(_.numUniquePassengers > 0))
  }

  def getOutOfServiceVehicles: mutable.HashMap[Id[BeamVehicle], RideHailAgentLocation] = {
    outOfServiceRideHailVehicles
  }

  def getIdleVehiclesAndFilterOutExluded: mutable.HashMap[Id[BeamVehicle], RideHailAgentLocation] = {
    idleRideHailVehicles.filter(elem => !rideHailManager.doNotUseInAllocation.contains(elem._1))
  }

  def getIdleAndInServiceVehicles: Map[Id[BeamVehicle], RideHailAgentLocation] = {
    (idleRideHailVehicles.toMap ++ inServiceRideHailVehicles.toMap)
      .filterNot(elem => rideHailManager.doNotUseInAllocation.contains(elem._1))
  }

  // This is faster implementation in case if you use `getIdleAndInServiceVehicles` to do a lookup only for one vehicle id
  def getRideHailAgentLocationInIdleAndInServiceVehicles(vehicleId: Id[BeamVehicle]): Option[RideHailAgentLocation] = {
    if (rideHailManager.doNotUseInAllocation.contains(vehicleId))
      None
    else {
      idleRideHailVehicles.get(vehicleId).orElse(inServiceRideHailVehicles.get(vehicleId))
    }
  }

  def getServiceStatusOf(vehicleId: Id[BeamVehicle]): RideHailManagerHelper.RideHailServiceStatus = {
    if (idleRideHailVehicles.contains(vehicleId)) {
      Available
    } else if (inServiceRideHailVehicles.contains(vehicleId)) {
      InService
    } else if (outOfServiceRideHailVehicles.contains(vehicleId)) {
      OutOfService
    } else if (refuelingRideHailVehicles.contains(vehicleId)) {
      Refueling
    } else {
      logger.error(s"Vehicle {} does not have a service status, assuming out of service", vehicleId)
      OutOfService
    }
  }

  /**
    * This will go through all in-motion vehicles (i.e. all vehicles in inService and refueling spatial indices)
    * and update the location of the agent in that spatial index based on where they are in the current beamLeg at
    * time tick.
    *
    * @param tick
    */
  def updateSpatialIndicesForMovingVehiclesToNewTick(tick: Int) = {
    if (tick > latestSpatialIndexUpdateTick) {
      (inServiceRideHailVehicles ++ refuelingRideHailVehicles).foreach { veh =>
        updateLocationOfAgent(
          veh._1,
          SpaceTime(veh._2.getCurrentLocationUTM(tick, rideHailManager.beamServices), tick)
        )
      }
      latestSpatialIndexUpdateTick = tick
    }
  }

  def updateLatestObservedTick(vehicleId: Id[BeamVehicle], tick: Int): Boolean = {
    // Update with latest tick
    val locationWithLatest = getRideHailAgentLocation(vehicleId)
      .copy(
        latestTickExperienced = tick
      )
    getServiceStatusOf(vehicleId) match {
      case InService =>
        putIntoService(locationWithLatest)
      case Available =>
        makeAvailable(locationWithLatest)
      case OutOfService =>
        putOutOfService(locationWithLatest)
      case Refueling =>
        putRefueling(locationWithLatest)
    }
  }

  def updatePassengerSchedule(
    vehicleId: Id[BeamVehicle],
    passengerSchedule: Option[PassengerSchedule],
    passengerScheduleIndex: Option[Int]
  ): Boolean = {
    // Update with latest passenger schedule
    val locationWithLatest = getRideHailAgentLocation(vehicleId)
      .copy(
        currentPassengerSchedule = passengerSchedule,
        currentPassengerScheduleIndex = passengerScheduleIndex
      )
    getServiceStatusOf(vehicleId) match {
      case InService =>
        putIntoService(locationWithLatest)
      case Available =>
        makeAvailable(locationWithLatest)
      case OutOfService =>
        putOutOfService(locationWithLatest)
      case Refueling =>
        putRefueling(locationWithLatest)
    }
  }

  def updateLocationOfAgent(
    vehicleId: Id[BeamVehicle],
    whenWhere: SpaceTime
  ) = {
    getServiceStatusOf(vehicleId) match {
      case Available =>
        idleRideHailVehicles.get(vehicleId) match {
          case Some(prevLocation) =>
            val newLocation = prevLocation.copy(latestUpdatedLocationUTM = whenWhere, serviceStatus = Some(Available))
            idleRideHailAgentSpatialIndex.remove(
              prevLocation.latestUpdatedLocationUTM.loc.getX,
              prevLocation.latestUpdatedLocationUTM.loc.getY,
              prevLocation
            )
            idleRideHailAgentSpatialIndex.put(
              newLocation.latestUpdatedLocationUTM.loc.getX,
              newLocation.latestUpdatedLocationUTM.loc.getY,
              newLocation
            )
            logger.debug(
              s"Updating Idle/Available with Id: $vehicleId == ${newLocation.vehicleId}; Full list before: ${idleRideHailVehicles.keys
                .mkString(";")}"
            )
            idleRideHailVehicles.put(newLocation.vehicleId, newLocation)
          case None => logger.info(s"None trying to update Idle/Available vehicle: $vehicleId")
        }
      case InService =>
        inServiceRideHailVehicles.get(vehicleId) match {
          case Some(prevLocation) =>
            val newLocation = prevLocation.copy(latestUpdatedLocationUTM = whenWhere, serviceStatus = Some(InService))
            inServiceRideHailAgentSpatialIndex.remove(
              prevLocation.latestUpdatedLocationUTM.loc.getX,
              prevLocation.latestUpdatedLocationUTM.loc.getY,
              prevLocation
            )
            inServiceRideHailAgentSpatialIndex.put(
              newLocation.latestUpdatedLocationUTM.loc.getX,
              newLocation.latestUpdatedLocationUTM.loc.getY,
              newLocation
            )
            logger.debug(
              s"Updating InService with Id: $vehicleId == ${newLocation.vehicleId}; Full list before: ${inServiceRideHailVehicles.keys
                .mkString(";")}"
            )
            inServiceRideHailVehicles.put(newLocation.vehicleId, newLocation)
          case None => logger.info(s"None trying to update InService vehicle: $vehicleId")
        }
      case OutOfService =>
        outOfServiceRideHailVehicles.get(vehicleId) match {
          case Some(prevLocation) =>
            val newLocation =
              prevLocation.copy(latestUpdatedLocationUTM = whenWhere, serviceStatus = Some(OutOfService))
            outOfServiceRideHailAgentSpatialIndex.remove(
              prevLocation.latestUpdatedLocationUTM.loc.getX,
              prevLocation.latestUpdatedLocationUTM.loc.getY,
              prevLocation
            )
            outOfServiceRideHailAgentSpatialIndex.put(
              newLocation.latestUpdatedLocationUTM.loc.getX,
              newLocation.latestUpdatedLocationUTM.loc.getY,
              newLocation
            )
            logger.debug(
              s"Updating OutOfService with Id: $vehicleId == ${newLocation.vehicleId}; Full list before: ${outOfServiceRideHailVehicles.keys
                .mkString(";")}"
            )
            outOfServiceRideHailVehicles.put(newLocation.vehicleId, newLocation)
          case None => logger.info(s"None trying to update OutOfService vehicle: $vehicleId")
        }
      case Refueling =>
        refuelingRideHailVehicles.get(vehicleId) match {
          case Some(prevLocation) =>
            val newLocation = prevLocation.copy(latestUpdatedLocationUTM = whenWhere, serviceStatus = Some(Refueling))
            refuelingRideHailAgentSpatialIndex.remove(
              prevLocation.latestUpdatedLocationUTM.loc.getX,
              prevLocation.latestUpdatedLocationUTM.loc.getY,
              prevLocation
            )
            refuelingRideHailAgentSpatialIndex.put(
              newLocation.latestUpdatedLocationUTM.loc.getX,
              newLocation.latestUpdatedLocationUTM.loc.getY,
              newLocation
            )
            logger.debug(
              s"Updating Refueling with Id: $vehicleId == ${newLocation.vehicleId}; Full list before: ${refuelingRideHailVehicles.keys
                .mkString(";")}"
            )
            refuelingRideHailVehicles.put(newLocation.vehicleId, newLocation)
          case None => logger.info(s"None trying to update Refueling vehicle: $vehicleId")
        }
    }
  }

  def makeAvailable(vehicleId: Id[BeamVehicle]): Boolean = {
    this.makeAvailable(getRideHailAgentLocation(vehicleId))
  }

  def makeAvailable(agentLocation: RideHailAgentLocation) = {
    addToIdle(agentLocation)
    removeFromInService(agentLocation)
    removeFromOutOfService(agentLocation)
    removeFromRefueling(agentLocation)
  }

  def putIntoService(vehicleId: Id[BeamVehicle]): Boolean = {
    this.putIntoService(getRideHailAgentLocation(vehicleId))
  }

  def putIntoService(agentLocation: RideHailAgentLocation) = {
    removeFromIdle(agentLocation)
    addToInService(agentLocation)
    removeFromOutOfService(agentLocation)
    removeFromRefueling(agentLocation)
  }

  def putOutOfService(vehicleId: Id[BeamVehicle]): Boolean = {
    this.putOutOfService(getRideHailAgentLocation(vehicleId))
  }

  def putOutOfService(agentLocation: RideHailAgentLocation) = {
    removeFromIdle(agentLocation)
    removeFromInService(agentLocation)
    addToOutOfService(agentLocation)
    removeFromRefueling(agentLocation)
  }

  def putRefueling(vehicleId: Id[BeamVehicle]): Boolean = {
    this.putRefueling(getRideHailAgentLocation(vehicleId))
  }

  def putRefueling(agentLocation: RideHailAgentLocation) = {
    removeFromIdle(agentLocation)
    removeFromInService(agentLocation)
    removeFromOutOfService(agentLocation)
    addToRefueling(agentLocation)
  }

  def addToIdle(agentLocation: RideHailAgentLocation) = {
    logger.debug(
      s"Making vehicle '${agentLocation.vehicleId}' Idle/Available; Full list before: ${idleRideHailVehicles.keys.mkString(";")}"
    )
    idleRideHailVehicles.put(agentLocation.vehicleId, agentLocation.copy(serviceStatus = Some(Available)))
    idleRideHailAgentSpatialIndex.put(
      agentLocation.latestUpdatedLocationUTM.loc.getX,
      agentLocation.latestUpdatedLocationUTM.loc.getY,
      agentLocation.copy(serviceStatus = Some(Available))
    )
  }

  def addToInService(agentLocation: RideHailAgentLocation) = {
    logger.debug(
      s"Making vehicle '${agentLocation.vehicleId}' InService; Full list before: ${inServiceRideHailVehicles.keys.mkString(";")}"
    )
    inServiceRideHailVehicles.put(agentLocation.vehicleId, agentLocation.copy(serviceStatus = Some(InService)))
    inServiceRideHailAgentSpatialIndex.put(
      agentLocation.latestUpdatedLocationUTM.loc.getX,
      agentLocation.latestUpdatedLocationUTM.loc.getY,
      agentLocation.copy(serviceStatus = Some(InService))
    )
  }

  def addToOutOfService(agentLocation: RideHailAgentLocation) = {
    logger.debug(
      s"Making vehicle '${agentLocation.vehicleId}' OutOfService; Full list before: ${outOfServiceRideHailVehicles.keys.mkString(";")}"
    )
    outOfServiceRideHailVehicles.put(agentLocation.vehicleId, agentLocation.copy(serviceStatus = Some(OutOfService)))
    outOfServiceRideHailAgentSpatialIndex.put(
      agentLocation.latestUpdatedLocationUTM.loc.getX,
      agentLocation.latestUpdatedLocationUTM.loc.getY,
      agentLocation.copy(serviceStatus = Some(OutOfService))
    )
  }

  def removeFromIdle(agentLocation: RideHailAgentLocation) = {
    logger.debug(
      s"Removing vehicle '${agentLocation.vehicleId}' from Idle/Available since will be InService; Full list before: ${idleRideHailVehicles.keys
        .mkString(";")}"
    )
    idleRideHailVehicles.remove(agentLocation.vehicleId)
    idleRideHailAgentSpatialIndex.remove(
      agentLocation.latestUpdatedLocationUTM.loc.getX,
      agentLocation.latestUpdatedLocationUTM.loc.getY,
      agentLocation
    )
  }

  def removeFromInService(agentLocation: RideHailAgentLocation) = {
    logger.debug(
      s"Removing vehicle '${agentLocation.vehicleId}' from InService; Full list before: ${inServiceRideHailVehicles.keys
        .mkString(";")}"
    )
    inServiceRideHailVehicles.remove(agentLocation.vehicleId)
    inServiceRideHailAgentSpatialIndex.remove(
      agentLocation.latestUpdatedLocationUTM.loc.getX,
      agentLocation.latestUpdatedLocationUTM.loc.getY,
      agentLocation
    )
  }

  def removeFromOutOfService(agentLocation: RideHailAgentLocation) = {
    logger.debug(
      s"Removing vehicle '${agentLocation.vehicleId}' from OutOfService since will be Idle/Available; Full list before: ${outOfServiceRideHailVehicles.keys
        .mkString(";")}"
    )
    outOfServiceRideHailVehicles.remove(agentLocation.vehicleId)
    outOfServiceRideHailAgentSpatialIndex.remove(
      agentLocation.latestUpdatedLocationUTM.loc.getX,
      agentLocation.latestUpdatedLocationUTM.loc.getY,
      agentLocation
    )
  }

  def addToRefueling(agentLocation: RideHailAgentLocation) = {
    logger.debug(
      s"Making vehicle '${agentLocation.vehicleId}' Refueling; Full list before: ${refuelingRideHailVehicles.keys.mkString(";")}"
    )
    refuelingRideHailVehicles.put(agentLocation.vehicleId, agentLocation.copy(serviceStatus = Some(Refueling)))
    refuelingRideHailAgentSpatialIndex.put(
      agentLocation.latestUpdatedLocationUTM.loc.getX,
      agentLocation.latestUpdatedLocationUTM.loc.getY,
      agentLocation.copy(serviceStatus = Some(Refueling))
    )
  }

  def removeFromRefueling(agentLocation: RideHailAgentLocation) = {
    logger.debug(
      s"Removing vehicle '${agentLocation.vehicleId}' from Refueling; Full list before: ${refuelingRideHailVehicles.keys
        .mkString(";")}"
    )
    refuelingRideHailVehicles.remove(agentLocation.vehicleId)
    refuelingRideHailAgentSpatialIndex.remove(
      agentLocation.latestUpdatedLocationUTM.loc.getX,
      agentLocation.latestUpdatedLocationUTM.loc.getY,
      agentLocation
    )
  }

  def getCandidateVehiclesForPoolingAssignment: Iterable[RideHailAgentLocation] = {
    (rideHailManager.beamScenario.beamConfig.beam.agentsim.agents.rideHail.allocationManager.requestBufferTimeoutInSeconds match {
      case 0 =>
        getIdleVehiclesAndFilterOutExluded.values
      case _ =>
        getIdleAndInServiceVehicles.values
    }).filter { veh =>
      rideHailManager
        .resources(veh.vehicleId)
        .getTotalRemainingRange - rideHailManager.beamScenario.beamConfig.beam.agentsim.agents.rideHail.rangeBufferForDispatchInMeters > 0
    }
  }

}

object RideHailManagerHelper {

  private[ridehail] def selectAgentsInRadius(
    pickupLocation: Location,
    radius: Double,
    searchAt: ParSeq[QuadTree[RideHailAgentLocation]]
  ): ParSeq[RideHailAgentLocation] = {
    searchAt.flatMap { source =>
      source.getDisk(pickupLocation.getX, pickupLocation.getY, radius).asScala
    }
  }

  private[ridehail] def filterETALessThanMaxWaitingTimeAndWithEnoughFuel(
    maxWaitingTimeInSec: Double,
    allAgentsETA: ParIterable[RideHailAgentETA],
    resources: mutable.Map[Id[BeamVehicle], BeamVehicle],
    bufferForDispatchInMeters: Int
  ): ParIterable[RideHailAgentETA] = {
    allAgentsETA
      .filter { x =>
        x.timeToCustomer <= maxWaitingTimeInSec
      }
      .filter { x =>
        resources(x.agentLocation.vehicleId).getTotalRemainingRange - bufferForDispatchInMeters > x.distance
      }
  }

  def RideHailAgentLocationFromVehicleAndSchedule(vehicleAndSchedule: VehicleAndSchedule): RideHailAgentLocation = {
    RideHailAgentLocation(
      vehicleAndSchedule.vehicle.getDriver.get,
      vehicleAndSchedule.vehicle.id,
      vehicleAndSchedule.vehicle.beamVehicleType,
      SpaceTime(vehicleAndSchedule.schedule.last.activity.getCoord, vehicleAndSchedule.schedule.last.serviceTime)
    )
  }

  /** Please be careful when use it as a Key in Map/Set. It has overridden `equals` and `hashCode` which only respects `vehicleId`
    */
  case class RideHailAgentLocation(
    rideHailAgent: ActorRef,
    vehicleId: Id[BeamVehicle],
    vehicleType: BeamVehicleType,
    latestUpdatedLocationUTM: SpaceTime,
    geofence: Option[Geofence] = None,
    currentPassengerSchedule: Option[PassengerSchedule] = None,
    currentPassengerScheduleIndex: Option[Int] = None,
    servingPooledTrip: Boolean = false,
    latestTickExperienced: Int = 0,
    serviceStatus: Option[RideHailServiceStatus] = None
  ) {

    /**
      * Returns the current location of the RideHailAgent based on the currentTick parameter. This accounts for where the
      * agent would be along the route if the currentTick is between the start and end times of the BeamLeg currently underway.
      *
      * @param currentTick
      * @param beamServices
      * @return
      */
    def getCurrentLocationUTM(currentTick: Int, beamServices: BeamServices): Location = {
      val mostRecentTimeToUse = Math.max(currentTick, latestUpdatedLocationUTM.time)
      currentPassengerSchedule
        .map(_.locationAtTime(mostRecentTimeToUse, beamServices))
        .getOrElse(latestUpdatedLocationUTM.loc)
    }

    def toStreetVehicle: StreetVehicle = {
      StreetVehicle(
        vehicleId,
        vehicleType.id,
        latestUpdatedLocationUTM,
        CAR,
        asDriver = true,
        needsToCalculateCost = true
      )
    }

    override def equals(obj: Any): Boolean = {
      obj match {
        case that: RideHailAgentLocation =>
          that.canEqual(this) && vehicleId == that.vehicleId
      }
    }

    override def hashCode(): Int = {
      vehicleId.hashCode()
    }

    def canEqual(other: Any): Boolean = other.isInstanceOf[RideHailAgentLocation]
  }

  case class RideHailAgentETA(
    agentLocation: RideHailAgentLocation,
    distance: Double,
    timeToCustomer: Int,
    totalServiceTime: Int
  )
  sealed trait RideHailServiceStatus
  /* Available means vehicle can be assigned to a new customer */
  case object Available extends RideHailServiceStatus
  case object InService extends RideHailServiceStatus
  case object OutOfService extends RideHailServiceStatus
  case object Refueling extends RideHailServiceStatus
}
