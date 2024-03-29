package beam.agentsim.infrastructure.power

import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.infrastructure.ChargingNetwork.{ChargingCycle, ChargingStation, ChargingVehicle}
import beam.agentsim.infrastructure.ChargingNetworkManager.ChargingNetworkHelper
import beam.agentsim.infrastructure.ParkingStall
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.charging.ChargingPointType.getChargingPointInstalledPowerInKw
import beam.agentsim.infrastructure.parking.ParkingZoneId
import beam.agentsim.infrastructure.power.PowerManager._
import beam.agentsim.infrastructure.taz.TAZ
import beam.cosim.helics.BeamHelicsInterface.{
  createFedInfo,
  enterExecutionMode,
  getFederate,
  BeamFederate,
  BeamFederateDescriptor
}
import beam.router.skim.event
import beam.sim.BeamServices
import beam.sim.config.BeamConfig.Beam.Agentsim.ChargingNetworkManager
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.{immutable, mutable}
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Try}

class SitePowerManager(chargingNetworkHelper: ChargingNetworkHelper, beamServices: BeamServices) extends LazyLogging {

  import SitePowerManager._

  private val cnmConfig = beamServices.beamConfig.beam.agentsim.chargingNetworkManager
  private val temporaryLoadEstimate = mutable.HashMap.empty[ChargingStation, Double]
  private val spmConfigMaybe = cnmConfig.sitePowerManagerController
  protected val powerController = new PowerManager(chargingNetworkHelper, beamServices.beamConfig)
  private var zonalPowerLimits = Map.empty[Id[ParkingZoneId], ZonalPowerLimit]
  private var vehiclePowerCommands = Map.empty[Id[BeamVehicle], PowerInKW]

  private[power] lazy val beamFederateMap: List[BeamFederateDescriptor] = spmConfigMaybe match {
    case Some(spmConfig) if spmConfig.connect => createBeamFederates(spmConfig)
    case _                                    => List.empty[BeamFederateDescriptor]
  }

  private def createBeamFederates(
    spmConfig: ChargingNetworkManager.SitePowerManagerController
  ): List[BeamFederateDescriptor] = {
    logger.warn("ChargingNetworkManager should connect to a site power controller via Helics...")
    val maybeListOfFederateDescriptors = Try {
      val tazIdsToChargingStations: Map[Set[Id[TAZ]], List[ChargingStation]] =
        randomlyGroupChargingStations(spmConfig.numberOfFederates, chargingNetworkHelper.allChargingStations)
      val numFederates = tazIdsToChargingStations.size
      val fedInfo = createFedInfo(
        spmConfig.coreType,
        s"--federates=$numFederates --broker_address=${spmConfig.brokerAddress}",
        spmConfig.timeDeltaProperty,
        spmConfig.intLogLevel
      )
      logger.info(s"Init $numFederates SitePowerManager Federates ...")
      tazIdsToChargingStations.zipWithIndex.map { case ((tazIds, stations), idx) =>
        val beamFedName = spmConfig.beamFederatePrefix + idx
        val spmFedNameSub = spmConfig.spmFederatePrefix + idx + "/" + spmConfig.spmFederateSubscription
        val federate = getFederate(
          beamFedName,
          fedInfo,
          spmConfig.bufferSize,
          cnmConfig.timeStepInSeconds,
          Some(spmConfig.beamFederatePublication),
          if (spmConfig.expectFeedback) Some(spmFedNameSub) else None
        )
        logger.debug("Got federate {} for taz {}", federate.fedName, tazIds.mkString(","))
        (tazIds, stations, federate)
      }.seq
    }.map { federates =>
      logger.info("Initialized {} federates, now they are going to execution mode", federates.size)
      val beamFederates = federates.map { case (_, _, beamFederate: BeamFederate) => beamFederate }.toSeq
      enterExecutionMode(1.hour, beamFederates: _*)
      logger.info("Entered execution mode")
      federates.map { case (tazIds, chargingStations, beamFederate) =>
        BeamFederateDescriptor(tazIds, chargingStations, beamFederate)
      }.toList
    }.recoverWith { case e =>
      logger.warn(
        s"Cannot initialize BeamFederate: ${e.getMessage}. ChargingNetworkManager is not connected to the SPMC"
      )
      Failure(e)
    }
    maybeListOfFederateDescriptors.getOrElse(List.empty[BeamFederateDescriptor])
  }

  private def randomlyGroupChargingStations(
    numberOfGroups: Int,
    chargingStations: Map[Id[ParkingZoneId], ChargingStation]
  ): Map[Set[Id[TAZ]], List[ChargingStation]] = {
    val tazIdToStations = chargingStations.values
      .groupBy(_.zone.tazId)
      .toList

    def listOfPairsToPair(
      pairs: Seq[(Id[TAZ], Iterable[ChargingStation])]
    ): (Set[Id[TAZ]], List[ChargingStation]) = {
      val (tazsSet, stationsBuffer) =
        pairs.foldLeft(mutable.Set.empty[Id[TAZ]], mutable.ListBuffer.empty[ChargingStation]) {
          case ((allTazs, allStations), (tazId, stations)) =>
            allTazs.add(tazId)
            allStations.appendAll(stations)
            (allTazs, allStations)
        }
      (tazsSet.toSet, stationsBuffer.toList)
    }

    val groupSize = {
      var groupSize1: Int = tazIdToStations.size / numberOfGroups
      // because of rounding we might want to slightly increase the size of a group
      while (groupSize1 * numberOfGroups < tazIdToStations.size) {
        groupSize1 = groupSize1 + 1
      }
      groupSize1
    }

    val groupedStations = scala.util.Random
      .shuffle(tazIdToStations)
      .grouped(groupSize)

    groupedStations.map(listOfPairsToPair).toMap
  }

  /**
    * This method adds hour**(hour - 1) to initial departure time estimate
    *
    * @param currentTime                  time of simulation
    * @param initialDepartureTimeEstimate initial estimate of departure time estimate
    * @return
    */
  private def estimateDepartureTime(currentTime: Int, initialDepartureTimeEstimate: Int): Int = {
    val ceiledInitialEstimate = 60 * Math.ceil(initialDepartureTimeEstimate / 60.0).toInt
    val additionalHoursForDeparture = if (ceiledInitialEstimate <= currentTime) {
      val hourDifference = ((ceiledInitialEstimate - currentTime) / 3600.0).toInt
      (1 + hourDifference * (hourDifference - 1)) * 3600
    } else 0
    ceiledInitialEstimate + additionalHoursForDeparture
  }

  /**
    * @param timeBin Int
    */
  def obtainPowerCommandsAndLimits(timeBin: Int): Unit = {
    logger.debug(s"obtainPowerCommandsAndLimits timeBin = $timeBin")
    vehiclePowerCommands = beamFederateMap.par
      .map { case BeamFederateDescriptor(groupedTazIds, stations, federate) =>
        val eventsToSend: List[Map[String, Any]] = if (timeBin <= 0) {
          // Constructing a list of Parking Zone ids to initialize the site power manager
          stations.map(station =>
            Map(
              "tazId"                      -> station.zone.tazId,
              "parkingZoneId"              -> station.zone.parkingZoneId,
              "parkingZonePowerInKW"       -> station.maxPlugPower * station.numPlugs,
              "parkingZoneNumPlugs"        -> station.numPlugs,
              "sitePowerManager"           -> station.zone.sitePowerManager.getOrElse("None"),
              "energyStorageCapacityInKWh" -> station.zone.energyStorageCapacityInKWh.getOrElse(0.0),
              "energyStorageSOC"           -> station.zone.energyStorageSOC.getOrElse(0.0)
            )
          )
        } else {
          stations.flatMap(s => s.vehiclesCurrentlyCharging ++ s.vehiclesDoneCharging).map {
            case (_, cv @ ChargingVehicle(vehicle, stall, station, arrivalTime, _, _, _, _, _, _, _, _, _)) =>
              // Sending this message
              val fuelCapacity = vehicle.beamVehicleType.primaryFuelCapacityInJoule
              Map(
                "tazId"                      -> stall.tazId,
                "parkingZoneId"              -> stall.parkingZoneId,
                "parkingZonePowerInKW"       -> station.maxPlugPower * station.numPlugs,
                "parkingZoneNumPlugs"        -> station.numPlugs,
                "vehicleId"                  -> vehicle.id,
                "vehicleType"                -> vehicle.beamVehicleType.id,
                "primaryFuelLevelInJoules"   -> vehicle.primaryFuelLevelInJoules,
                "primaryFuelCapacityInJoule" -> vehicle.beamVehicleType.primaryFuelCapacityInJoule,
                "arrivalTime"                -> arrivalTime,
                "departureTime"              -> estimateDepartureTime(timeBin, cv.estimatedDepartureTime),
                "desiredFuelLevelInJoules"   -> (fuelCapacity - vehicle.primaryFuelLevelInJoules),
                "maxPowerInKW" -> vehicle.beamVehicleType.chargingCapability
                  .map(getChargingPointInstalledPowerInKw)
                  .map(Math.min(getChargingPointInstalledPowerInKw(stall.chargingPointType.get), _))
                  .getOrElse(getChargingPointInstalledPowerInKw(stall.chargingPointType.get))
              )
          }
        }
        val vehicleIdToPowerInKW: immutable.Seq[(Id[BeamVehicle], PowerInKW)] = federate
          .cosimulate(timeBin, eventsToSend)
          .flatMap { message =>
            val messageContainsExpectedTazId = message.get("tazId") match {
              case Some(tazIdStr) =>
                val maybeTaz = beamServices.beamScenario.tazTreeMap.getTAZ(tazIdStr.toString)
                maybeTaz.exists(taz => groupedTazIds.contains(taz.tazId))
              case _ => false
            }
            // chargingNetworkHelper.allChargingStations
            val messageContainsVehicleAndParkingZoneId =
              message.contains("vehicleId") && message.contains("parkingZoneId")
            if (
              spmConfigMaybe.get.expectFeedback && messageContainsVehicleAndParkingZoneId && messageContainsExpectedTazId
            ) {
              val stationId = Id.create(message("parkingZoneId").toString, classOf[ParkingZoneId])
              val vehicleId = Id.create(message("vehicleId").toString, classOf[BeamVehicle])
              chargingNetworkHelper.allChargingStations.get(stationId).flatMap(_.vehicles.get(vehicleId)) match {
                case Some(chargingVehicle) => Some(chargingVehicle.vehicle.id -> message("powerInKW").toString.toDouble)
                case _ =>
                  logger.error(s"The vehicle $vehicleId might have already left the station $stationId!")
                  None
              }
            } else None
          }
        vehicleIdToPowerInKW.toMap
      }
      .foldLeft(Map.empty[Id[BeamVehicle], PowerInKW])(_ ++ _)
    val loadEstimate = chargingNetworkHelper.allChargingStations.par.map { case (_, station) =>
      station -> temporaryLoadEstimate.getOrElse(station, 0.0)
    }.seq
    logger.debug("Total Load estimated is {} at tick {}", loadEstimate.values.sum, timeBin)
    zonalPowerLimits = powerController.obtainPowerPhysicalBounds(timeBin, loadEstimate)
  }

  private def getPowerFromZoneLimit(stall: ParkingStall): PowerInKW = {
    val stallPower = ChargingPointType.getChargingPointInstalledPowerInKw(stall.chargingPointType.get)
    zonalPowerLimits.get(stall.parkingZoneId).map(_.getPlugPower).getOrElse(stallPower)
  }

  def getPowerFromVehicleLimit(
    vehicle: BeamVehicle,
    stall: ParkingStall,
    constrainedPowerAtZoneLevel: Option[PowerInKW] = None
  ): PowerInKW = {
    val powerLimit1 = constrainedPowerAtZoneLevel.getOrElse(getPowerFromZoneLimit(stall))
    val powerLimit2 = vehicle.beamVehicleType.chargingCapability
      .map(ChargingPointType.getChargingPointInstalledPowerInKw)
      .getOrElse(powerLimit1)
    Math.min(vehiclePowerCommands.getOrElse(vehicle.id, powerLimit2), powerLimit2)
  }

  /**
    * @param chargingVehicle the vehicle being charging
    * @return
    */
  def dispatchEnergy(
    cycleStartTime: Int,
    cycleEndTime: Int,
    maxCycleDuration: Int,
    chargingVehicle: ChargingVehicle
  ): ChargingCycle = {
    val vehicle = chargingVehicle.vehicle
    val constrainedPowerAtZoneLevel = getPowerFromZoneLimit(chargingVehicle.stall)
    val constrainedPowerAtVehicleLevel =
      getPowerFromVehicleLimit(vehicle, chargingVehicle.stall, Some(constrainedPowerAtZoneLevel))
    val duration = Math.max(0, cycleEndTime - cycleStartTime)
    val (chargingDuration, energyToCharge) = vehicle.refuelingSessionDurationAndEnergyInJoules(
      sessionDurationLimit = Some(duration),
      stateOfChargeLimit = None,
      chargingPowerLimit = Some(constrainedPowerAtVehicleLevel)
    )
    val (totChargingDuration, _) = vehicle.refuelingSessionDurationAndEnergyInJoules(
      sessionDurationLimit = None,
      stateOfChargeLimit = None,
      chargingPowerLimit = Some(constrainedPowerAtVehicleLevel)
    )
    val (_, energyToChargeIfUnconstrained) = vehicle.refuelingSessionDurationAndEnergyInJoules(
      sessionDurationLimit = Some(duration),
      stateOfChargeLimit = None,
      chargingPowerLimit = Some(constrainedPowerAtZoneLevel)
    )
    if ((chargingDuration > 0 && energyToCharge == 0) || chargingDuration == 0 && energyToCharge > 0) {
      logger.debug(
        s"chargingDuration is $chargingDuration while energyToCharge is $energyToCharge. " +
        s"Something is broken or due to physical bounds!!"
      )
    }
    val theActualEndTime = cycleStartTime + chargingDuration
    ChargingCycle(
      cycleStartTime,
      theActualEndTime,
      constrainedPowerAtVehicleLevel,
      energyToCharge,
      energyToChargeIfUnconstrained,
      totChargingDuration - chargingDuration,
      maxCycleDuration
    )
  }

  /**
    * Collect rough power demand per vehicle
    *
    * @param time                          start time of charging cycle
    * @param duration                      duration of charging cycle
    * @param energyToChargeIfUnconstrained the energy to charge
    * @param station                       the station where vehicle is charging
    */
  def collectObservedLoadInKW(
    time: Int,
    duration: Int,
    energyToChargeIfUnconstrained: Double,
    station: ChargingStation
  ): Unit = {
    val requiredLoad = if (duration == 0) 0.0 else (energyToChargeIfUnconstrained / 3.6e+6) / (duration / 3600.0)
    // Keep track of previous time bin load
    temporaryLoadEstimate.synchronized {
      val requiredLoadAcc = temporaryLoadEstimate.getOrElse(station, 0.0) + requiredLoad
      temporaryLoadEstimate.put(station, requiredLoadAcc)
    }
    beamServices.matsimServices.getEvents.processEvent(
      event.TAZSkimmerEvent(
        cnmConfig.timeStepInSeconds * (time / cnmConfig.timeStepInSeconds),
        new Coord(0, 0),
        station.zone.parkingZoneId.toString,
        requiredLoad,
        beamServices,
        "CNM",
        geoIdMaybe = Some(station.zone.tazId.toString)
      )
    )
  }

  def close(): Unit = {
    powerController.close()
    beamFederateMap.foreach { case BeamFederateDescriptor(_, _, federate) => federate.close() }
  }
}

object SitePowerManager {

  case class ZonalPowerLimit(parkingZoneId: Id[ParkingZoneId], numPlugs: Int, totPowerInKW: PowerInKW) {
    def getPlugPower: PowerInKW = totPowerInKW / numPlugs
  }
}
