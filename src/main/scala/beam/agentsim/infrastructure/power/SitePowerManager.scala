package beam.agentsim.infrastructure.power

import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.vehicles.VehicleManager.ReservedFor
import beam.agentsim.infrastructure.ChargingNetwork.{ChargingCycle, ChargingStation, ChargingVehicle}
import beam.agentsim.infrastructure.ChargingNetworkManager.ChargingNetworkHelper
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.charging.ChargingPointType.getChargingPointInstalledPowerInKw
import beam.agentsim.infrastructure.parking.ParkingType
import beam.agentsim.infrastructure.power.PowerManager._
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
  private val spmcConfigMaybe = cnmConfig.sitePowerManagerController
  protected val powerController = new PowerManager(chargingNetworkHelper, beamServices.beamConfig)
  protected var powerLimits = Map.empty[ChargingStation, PowerInKW]
  protected var powerCommands = Map.empty[Id[BeamVehicle], PowerInKW]

  private[power] lazy val beamFederateMap: List[(Id[_], List[ChargingStation], BeamFederate)] = spmcConfigMaybe match {
    case Some(spmcConfig) if spmcConfig.connect =>
      logger.warn("ChargingNetworkManager should connect to a site power controller via Helics...")
      Try {
        val tazIdToChargingStations = chargingNetworkHelper.allChargingStations.groupBy(_.zone.tazId)
        logger.info("Init SitePowerManager Federates for {} TAZes...", tazIdToChargingStations.size)
        val fedInfo = createFedInfo(
          spmcConfig.coreType,
          spmcConfig.coreInitString,
          spmcConfig.timeDeltaProperty,
          spmcConfig.intLogLevel
        )
        tazIdToChargingStations.map { case (tazId, stations) =>
          val fedName = spmcConfig.federatesPrefix + tazId.toString
          logger.debug("getting federate {}", fedName)
          val federate = getFederate(
            fedName, //BEAM_SPM_FEDERATE_1
            fedInfo,
            spmcConfig.bufferSize,
            beamServices.beamConfig.beam.agentsim.chargingNetworkManager.timeStepInSeconds,
            spmcConfig.federatesPublication match {
              case s: String if s.nonEmpty => Some(s)
              case _                       => None
            },
            (
              spmcConfig.spmcFederatesPrefix + tazId.toString,
              spmcConfig.spmcSubscription,
              spmcConfig.expectFeedback
            ) match {
              case (s1: String, s2: String, feedback: Boolean) if s1.nonEmpty && s2.nonEmpty && feedback =>
                Some(s1 + "/" + s2) // SPMC_FEDERATE_1/CHARGING_PROFILE
              case _ => None
            }
          )
          logger.debug("Got federate for taz {}", tazId.toString)
          (tazId, stations, federate)
        }.seq
      }.map { federates =>
        logger.info("Initialized {} federates, now they are going to execution mode", federates.size)
        enterExecutionMode(1.hour, federates.map(_._3).toSeq: _*)
        logger.info("Entered execution mode")
        federates.toList
      }.recoverWith { case e =>
        logger.warn(
          s"Cannot initialize BeamFederate: ${e.getMessage}. " +
          "ChargingNetworkManager is not connected to the SPMC"
        )
        Failure(e)
      }.getOrElse(List.empty[(Id[_], List[ChargingStation], BeamFederate)])
    case _ => List.empty[(Id[_], List[ChargingStation], BeamFederate)]
  }

  /**
    * @param timeBin
    */
  def obtainPowerCommandsAndLimits(timeBin: Int): Unit = {
    val numPluggedVehicles = chargingNetworkHelper.allChargingStations.view.map(_.howManyVehiclesAreCharging).sum
    logger.debug(
      s"obtainPowerCommandsAndLimits timeBin = $timeBin, numPluggedVehicles = $numPluggedVehicles"
    )
    powerCommands = beamFederateMap.par
      .map { case (tazId, stations, federate) =>
        federate
          .cosimulate(
            timeBin,
            stations.flatMap(_.connectedVehicles.values).map {
              case ChargingVehicle(vehicle, stall, _, arrivalTime, _, _, _, _, _, _, departureTime, _, _, _) =>
                // Sending this message
                val powerInKW = getChargingPointInstalledPowerInKw(stall.chargingPointType.get)
                Map(
                  "tazId"                    -> tazId,
                  "siteId"                   -> tazId, // TODO I have a way for generating the site Id, but for now Site id == parking Zone Id
                  "vehicleId"                -> vehicle.id,
                  "vehicleType"              -> vehicle.beamVehicleType.id,
                  "primaryFuelLevelInJoules" -> vehicle.primaryFuelLevelInJoules,
                  "arrivalTime"              -> arrivalTime, // TODO arrival time at station
                  "departureTime"            -> departureTime, // TODO estimated departure time = arrival time + parking duration
                  "desiredFuelLevelInJoules" -> (vehicle.beamVehicleType.primaryFuelCapacityInJoule - vehicle.primaryFuelLevelInJoules), // TODO Battery capacity - fuel level
                  "maxPowerInKW" -> vehicle.beamVehicleType.chargingCapability
                    .map(getChargingPointInstalledPowerInKw)
                    .map(Math.min(powerInKW, _))
                    .getOrElse(
                      powerInKW
                    ) // TODO power at stall: MIN(stall.chargingPointType,vehicle.vehicleType.chargingCapability)
                )
            }
          )
          .flatMap { message =>
            // Receiving this message
            chargingNetworkHelper
              .lookUpConnectedVehiclesAt(timeBin)
              .get(Id.create(message("vehicleId").asInstanceOf[String], classOf[BeamVehicle])) match {
              case Some(chargingVehicle) =>
                Some(chargingVehicle.vehicle.id -> message("powerInKw").asInstanceOf[PowerInKW])
              case _ =>
                logger.error(
                  s"Cannot find vehicle ${message("vehicleId")} obtained from the site power manager controller." +
                  s"Potentially the vehicle has already disconnected or something is broken"
                )
                None
            }
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
    val (remainingChargingDuration, _) = vehicle.refuelingSessionDurationAndEnergyInJoules(
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
      maxCycleDuration,
      remainingChargingDuration
    )
  }

  /**
    * Collect rough power demand per vehicle
    * @param time start time of charging cycle
    * @param duration duration of charging cycle
    * @param energyToChargeIfUnconstrained the energy to charge
    * @param station the station where vehicle is charging
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
