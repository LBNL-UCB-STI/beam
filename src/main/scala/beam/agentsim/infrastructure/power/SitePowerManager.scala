package beam.agentsim.infrastructure.power

import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.vehicles.VehicleManager.ReservedFor
import beam.agentsim.infrastructure.ChargingNetwork.{ChargingCycle, ChargingStation, ChargingVehicle}
import beam.agentsim.infrastructure.ChargingNetworkManager.ChargingNetworkHelper
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.ParkingType
import beam.agentsim.infrastructure.power.PowerManager._
import beam.cosim.helics.BeamHelicsInterface.{BeamFederate, getFederate}
import beam.router.skim.event
import beam.sim.BeamServices
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.mutable
import scala.util.{Failure, Try}

class SitePowerManager(chargingNetworkHelper: ChargingNetworkHelper, beamServices: BeamServices) extends LazyLogging {

  private val cnmConfig = beamServices.beamConfig.beam.agentsim.chargingNetworkManager
  private val temporaryLoadEstimate = mutable.HashMap.empty[ChargingStation, Double]
  private val spmcConfigMaybe = cnmConfig.sitePowerManagerController
  private val timeStep = cnmConfig.timeStepInSeconds
  private var currentBin = -1
  protected val powerController = new PowerManager(chargingNetworkHelper, beamServices.beamConfig)
  protected var physicalBounds = Map.empty[ChargingStation, PowerInKW]
  protected var chargingCommands = Map.empty[Id[BeamVehicle], PowerInKW]

  private[power] lazy val beamFederateList: List[BeamFederate] = spmcConfigMaybe match {
    case Some(spmcConfig) if spmcConfig.connect =>
      logger.warn("ChargingNetworkManager should connect to a site power controller via Helics...")
      Try {
        logger.info("Init SitePowerManager Federates...")
        chargingNetworkHelper.allChargingStations.par.map { station =>
          getFederate(
            spmcConfig.federatesPrefix + station.zone.sitePowerManagerId.toString,
            spmcConfig.coreType,
            spmcConfig.coreInitString,
            spmcConfig.timeDeltaProperty,
            spmcConfig.intLogLevel,
            spmcConfig.bufferSize,
            beamServices.beamConfig.beam.agentsim.chargingNetworkManager.timeStepInSeconds,
            spmcConfig.federatesPublication match {
              case s: String if s.nonEmpty => Some(s)
              case _                       => None
            },
            (
              spmcConfig.spmcFederatesPrefix + station.zone.sitePowerManagerId.toString,
              spmcConfig.spmcSubscription,
              spmcConfig.expectFeedback
            ) match {
              case (s1: String, s2: String, feedback: Boolean) if s1.nonEmpty && s2.nonEmpty && feedback =>
                Some(s1 + "/" + s2)
              case _ => None
            }
          )
        }.toList
      }.recoverWith { case e =>
        logger.warn("Cannot init BeamFederate: {}. ChargingNetworkManager is not connected to the grid", e.getMessage)
        Failure(e)
      }.get
    case _ => List.empty
  }

  def updateChargingProfiles(timeBin: Int) = {

  }

  /**
    * Get required power for electrical vehicles
    *
    * @param tick current time
    * @return power (in Kilo Watt) over planning horizon
    */
  def requiredPowerInKWOverNextPlanningHorizon(timeBin: Int): Map[ChargingStation, PowerInKW] = {
    val plans = if (beamFederateList.isEmpty) {

      chargingNetworkHelper.allChargingStations.par
        .map(station => station -> temporaryLoadEstimate.getOrElse(station, 0.0))
        .seq
        .toMap
    } else {
      beamFederateList.par.map {
        case beamFederate if currentBin < currentTime / timeStep =>
          chargingNetworkHelper.
          chargingNetworkHelper.allChargingStations.par.map(_.)
        case _ =>

      }
    }

    physicalBounds = powerController.obtainPowerPhysicalBounds(timeBin, Some(loadEstimate))
    log.debug("Total Load estimated is {} at tick {}", loadEstimate.values.sum, timeBin)
    currentBin = timeBin / timeStep
    temporaryLoadEstimate.clear()
    if (plans.isEmpty) {
      logger.error(s"Charging Replan did not produce allocations on tick: [$timeBin]")
    }
    plans
  }

  /**
    * @param chargingVehicle the vehicle being charging
    * @param physicalBounds physical bounds under which the dispatch occur
    * @return
    */
  def dispatchEnergy(cycleStartTime: Int, cycleEndTime: Int, maxCycleDuration: Int, chargingVehicle: ChargingVehicle): ChargingCycle = {
    val ChargingVehicle(vehicle, _, station, _, _, _, _, _, _, _, _) = chargingVehicle
    val unconstrainedPower = Math.min(station.maxPlugPower, chargingVehicle.chargingCapacityInKw)
    val constrainedPower = chargingCommands.getOrElse(vehicle.id, {
      Math.min(unconstrainedPower, physicalBounds.get(station).map(_/station.numPlugs).getOrElse(unconstrainedPower))
    })
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
    if ((chargingDuration > 0 && energyToCharge == 0) || chargingDuration == 0 && energyToCharge > 0) {
      logger.debug(
        s"chargingDuration is $chargingDuration while energyToCharge is $energyToCharge. " +
        s"Something is broken or due to physical bounds!!"
      )
    }
    val theActualEndTime = cycleStartTime + chargingDuration
    ChargingCycle(cycleStartTime, theActualEndTime, constrainedPower, energyToCharge, energyToChargeIfUnconstrained, maxCycleDuration)
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
        geoIdMaybe = Some(station.zone.geoId.toString)
      )
    )
  }


  def close(): Unit = {
    powerController.close()
    beamFederateOption.fold(logger.debug("Not connected to SPMC, just releasing helics resources"))(_.close())
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
    createId(s"site-${reservedFor}-${geoId}-${parkingType}-${chargingLevel}")
  }

  def createId(siteId: String): Id[SitePowerManager] = Id.create(siteId, classOf[SitePowerManager])
}
