package beam.agentsim.infrastructure.power

import beam.agentsim.infrastructure.ChargingNetwork.{ChargingStation, ChargingVehicle}
import beam.agentsim.infrastructure.ChargingNetworkManager.ChargingNetworkHelper
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.power.PowerController._
import beam.router.skim.event
import beam.sim.BeamServices
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Coord

import scala.collection.mutable

class SitePowerManager(
  chargingNetworkHelper: ChargingNetworkHelper,
  unlimitedPhysicalBounds: Map[ChargingStation, PhysicalBounds],
  beamServices: BeamServices
) extends LazyLogging {

  private val cnmConfig = beamServices.beamConfig.beam.agentsim.chargingNetworkManager
  private val temporaryLoadEstimate = mutable.HashMap.empty[ChargingStation, Double]

  /**
    * Get required power for electrical vehicles
    *
    * @param tick current time
    * @return power (in Kilo Watt) over planning horizon
    */
  def requiredPowerInKWOverNextPlanningHorizon(tick: Int): Map[ChargingStation, PowerInKW] = {
    val plans = chargingNetworkHelper.allChargingStations.par
      .map(station => station -> temporaryLoadEstimate.getOrElse(station, 0.0))
      .seq
      .toMap
    temporaryLoadEstimate.clear()
    if (plans.isEmpty) {
      logger.debug(s"Charging Replan did not produce allocations on tick: [$tick]")
    }
    plans
  }

  /**
    * @param chargingVehicle the vehicle being charging
    * @param physicalBounds physical bounds under which the dispatch occur
    * @return
    */
  def dispatchEnergy(
    timeInterval: Int,
    chargingVehicle: ChargingVehicle,
    physicalBounds: Map[ChargingStation, PhysicalBounds]
  ): (ChargingDurationInSec, EnergyInJoules, EnergyInJoules) = {
    val ChargingVehicle(vehicle, _, station, _, _, _, _, _, _, _, _) = chargingVehicle
    // dispatch
    val maxZoneLoad = physicalBounds(station).powerLimitUpper
    val maxUnlimitedZoneLoad = unlimitedPhysicalBounds(station).powerLimitUpper
    val chargingPointLoad =
      ChargingPointType.getChargingPointInstalledPowerInKw(station.zone.chargingPointType.get)
    val chargingPowerLimit = maxZoneLoad * chargingPointLoad / maxUnlimitedZoneLoad
    val (chargingDuration, energyToCharge) = vehicle.refuelingSessionDurationAndEnergyInJoules(
      sessionDurationLimit = Some(timeInterval),
      stateOfChargeLimit = None,
      chargingPowerLimit = Some(chargingPowerLimit)
    )
    val (_, energyToChargeIfUnconstrained) = vehicle.refuelingSessionDurationAndEnergyInJoules(
      sessionDurationLimit = Some(timeInterval),
      stateOfChargeLimit = None,
      chargingPowerLimit = None
    )
    if ((chargingDuration > 0 && energyToCharge == 0) || chargingDuration == 0 && energyToCharge > 0) {
      logger.debug(
        s"chargingDuration is $chargingDuration while energyToCharge is $energyToCharge. " +
        s"Something is broken or due to physical bounds!!"
      )
    }
    (chargingDuration, energyToCharge, energyToChargeIfUnconstrained)
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
}
