package beam.agentsim.infrastructure.power

import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.vehicles.VehicleManager.ReservedFor
import beam.agentsim.infrastructure.ChargingNetwork.{ChargingCycle, ChargingStation, ChargingVehicle}
import beam.agentsim.infrastructure.ChargingNetworkManager.ChargingNetworkHelper
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.charging.ChargingPointType.getChargingPointInstalledPowerInKw
import beam.agentsim.infrastructure.parking.{ParkingType, ParkingZoneId}
import beam.agentsim.infrastructure.power.PowerManager._
import beam.agentsim.infrastructure.taz.TAZ
import beam.cosim.helics.BeamHelicsInterface
import beam.cosim.helics.BeamHelicsInterface.{createFedInfo, enterExecutionMode, getFederate, BeamFederate}
import beam.router.skim.event
import beam.sim.BeamServices
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.mutable
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Try}

class SitePowerManager(chargingNetworkHelper: ChargingNetworkHelper, beamServices: BeamServices) extends LazyLogging {

  private val cnmConfig = beamServices.beamConfig.beam.agentsim.chargingNetworkManager
  private val temporaryLoadEstimate = mutable.HashMap.empty[ChargingStation, Double]
  private val spmConfigMaybe = cnmConfig.sitePowerManagerController
  protected val powerController = new PowerManager(chargingNetworkHelper, beamServices.beamConfig)
  protected var powerLimits = Map.empty[ChargingStation, PowerInKW]
  protected var powerCommands = Map.empty[Id[BeamVehicle], PowerInKW]

  protected val siteMap: Map[Id[ParkingZoneId], Id[TAZ]] =
    chargingNetworkHelper.allChargingStations
      .groupBy(x => (x.zone.tazId, x.zone.parkingZoneId))
      .keys
      .map { case (tazId, parkingZoneId) =>
        parkingZoneId -> tazId
      }
      .toMap

  private[power] lazy val beamFederateMap: List[(Id[TAZ], List[ChargingStation], BeamFederate)] =
    spmConfigMaybe match {
      case Some(spmConfig) if spmConfig.connect =>
        logger.warn("ChargingNetworkManager should connect to a site power controller via Helics...")
        Try {
          // the same should be in 'all_taz' in src/main/python/gemini/site_power_controller_multi_federate.py:311
          val tazIdToChargingStations =
            if (spmConfig.oneFederatePerTAZ)
              chargingNetworkHelper.allChargingStations.groupBy(_.zone.tazId.toString)
            else
              Map[String, List[ChargingStation]]("" -> chargingNetworkHelper.allChargingStations)
          val numTAZs = tazIdToChargingStations.size
          val fedInfo = createFedInfo(
            spmConfig.coreType,
            s"--federates=$numTAZs --broker_address=${spmConfig.brokerAddress}",
            spmConfig.timeDeltaProperty,
            spmConfig.intLogLevel
          )
          logger.info(s"Init SitePowerManager Federates for $numTAZs TAZes...")
          tazIdToChargingStations.map { case (tazIdStr, stations) =>
            val beamFedName = spmConfig.beamFederatePrefix + tazIdStr //BEAM_FED_TAZ(XXX)
            val spmFedNameSub = spmConfig.spmFederatePrefix + tazIdStr + "/" + spmConfig.spmFederateSubscription
            val federate = getFederate(
              beamFedName,
              fedInfo,
              spmConfig.bufferSize,
              cnmConfig.timeStepInSeconds,
              Some(spmConfig.beamFederatePublication),
              if (spmConfig.expectFeedback) Some(spmFedNameSub) else None
            )
            logger.debug("Got federate {} for taz {}", federate.fedName, tazIdStr)
            (Id.create(tazIdStr, classOf[TAZ]), stations, federate)
          }.seq
        }.map { federates =>
          logger.info("Initialized {} federates, now they are going to execution mode", federates.size)
          val beamFederates = federates.map { case (_, _, beamFederate: BeamFederate) => beamFederate }.toSeq
          enterExecutionMode(1.hour, beamFederates: _*)
          logger.info("Entered execution mode")
          federates.toList
        }.recoverWith { case e =>
          logger.warn(
            s"Cannot initialize BeamFederate: ${e.getMessage}. " +
            "ChargingNetworkManager is not connected to the SPMC"
          )
          Failure(e)
        }.getOrElse(List.empty[(Id[TAZ], List[ChargingStation], BeamFederate)])
      case _ => List.empty[(Id[TAZ], List[ChargingStation], BeamFederate)]
    }

  /**
    * This method adds hour**(hour - 1) to initial departure time estimate
    * @param currentTime time of simulation
    * @param initialDepartureTimeEstimate initial estimate of departure time estimate
    * @return
    */
  def estimateDepartureTime(currentTime: Int, initialDepartureTimeEstimate: Int): Int = {
    val ceiledInitialEstimate = 60 * Math.ceil(initialDepartureTimeEstimate / 60.0).toInt
    val additionalHoursForDeparture = if (ceiledInitialEstimate < currentTime) {
      val hourDifference = ((ceiledInitialEstimate - currentTime) / 3600.0).toInt
      (1 + hourDifference * (hourDifference - 1)) * 3600
    } else 0
    ceiledInitialEstimate + additionalHoursForDeparture
  }

  /**
    * @param timeBin Int
    */
  def obtainPowerCommandsAndLimits(timeBin: Int, connectedVehicles: Map[Id[BeamVehicle], ChargingVehicle]): Unit = {
    logger.debug(s"obtainPowerCommandsAndLimits timeBin = $timeBin")
    val parkingZoneToVehiclesMap = connectedVehicles.values.groupBy(_.chargingStation.zone.parkingZoneId)
    powerCommands = beamFederateMap.par
      .map { case (groupedTazId, _, federate) =>
        val eventsToSend: List[Map[String, Any]] = siteMap.flatMap { case (parkingZoneId, tazId) =>
          parkingZoneToVehiclesMap
            .get(parkingZoneId)
            .map(_.map { case cv @ ChargingVehicle(vehicle, stall, _, arrivalTime, _, _, _, _, _, _, _, _, _) =>
              // Sending this message
              val fuelCapacity = vehicle.beamVehicleType.primaryFuelCapacityInJoule
              Map(
                "tazId"                      -> tazId,
                "siteId"                     -> parkingZoneId,
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
            })
            .getOrElse(List(Map("tazId" -> tazId, "siteId" -> parkingZoneId)))
        }.toList
        federate
          .cosimulate(timeBin, eventsToSend)
          .flatMap { message =>
            // Receiving this message
            val feedback = spmConfigMaybe.get.expectFeedback && (message.get("tazId") match {
              case Some(tazIdStr) if spmConfigMaybe.get.oneFederatePerTAZ =>
                val taz = beamServices.beamScenario.tazTreeMap.getTAZ(tazIdStr.toString)
                if (taz.isEmpty || taz.get.tazId != groupedTazId) {
                  logger.error(s"The received tazId $tazIdStr from SPM Controller does not match tazId $groupedTazId")
                  false
                } else message.contains("vehicleId")
              case _ =>
                logger.debug(s"The received feedback from SPM Controller: $message")
                message.contains("vehicleId")
            })
            if (feedback) {
              val vehicleId = Id.create(message("vehicleId").toString, classOf[BeamVehicle])
              connectedVehicles.get(vehicleId) match {
                case Some(chargingVehicle) =>
                  Some(chargingVehicle.vehicle.id -> message("powerInKW").toString.toDouble)
                case _ =>
                  logger.error(s"The vehicle $vehicleId might have already left the station between co-simulation!")
                  None
              }
            } else None
          }
          .toMap
      }
      .foldLeft(Map.empty[Id[BeamVehicle], PowerInKW])(_ ++ _)

    val loadEstimate = chargingNetworkHelper.allChargingStations.par
      .map(station => station -> temporaryLoadEstimate.getOrElse(station, 0.0))
      .seq
      .toMap
    logger.debug("Total Load estimated is {} at tick {}", loadEstimate.values.sum, timeBin)
    powerLimits = powerController.obtainPowerPhysicalBounds(timeBin, loadEstimate)
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
    val station = chargingVehicle.chargingStation
    val unconstrainedPower = Math.min(station.maxPlugPower, chargingVehicle.chargingCapacityInKw)
    val constrainedPower = powerCommands.getOrElse(
      vehicle.id, {
        Math.min(
          unconstrainedPower,
          powerLimits.get(station).map(_ / station.numPlugs).getOrElse(unconstrainedPower)
        )
      }
    )
    val duration = Math.max(0, cycleEndTime - cycleStartTime)
    val (chargingDuration, energyToCharge) = vehicle.refuelingSessionDurationAndEnergyInJoules(
      sessionDurationLimit = Some(duration),
      stateOfChargeLimit = None,
      chargingPowerLimit = Some(constrainedPower)
    )
    val (_, energyToChargeIfUnconstrained) = vehicle.refuelingSessionDurationAndEnergyInJoules(
      sessionDurationLimit = Some(duration),
      stateOfChargeLimit = None,
      chargingPowerLimit = Some(unconstrainedPower)
    )
    val (totChargingDuration, _) = vehicle.refuelingSessionDurationAndEnergyInJoules(
      sessionDurationLimit = None,
      stateOfChargeLimit = None,
      chargingPowerLimit = Some(constrainedPower)
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
      constrainedPower,
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
    beamFederateMap.foreach { case (_, _, federate) => federate.close() }
    BeamHelicsInterface.closeHelics()
  }
}

object SitePowerManager {

  def constructSitePowerKey(
    reservedFor: ReservedFor,
    geoId: Id[_],
    parkingType: ParkingType,
    chargingPointTypeMaybe: Option[ChargingPointType]
  ): Id[SitePowerManager] = {
    val chargingLevel = chargingPointTypeMaybe match {
      case Some(chargingPointType) if ChargingPointType.isFastCharger(chargingPointType) => "Fast"
      case Some(_)                                                                       => "Slow"
      case _                                                                             => "NoCharger"
    }
    createId(s"site-$reservedFor-$geoId-$parkingType-$chargingLevel")
  }

  def createId(siteId: String): Id[SitePowerManager] = Id.create(siteId, classOf[SitePowerManager])
}
