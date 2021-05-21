package beam.agentsim.infrastructure.power

import beam.agentsim.agents.vehicles.{BeamVehicle, VehicleManager}
import beam.agentsim.infrastructure.ChargingNetwork
import beam.agentsim.infrastructure.ChargingNetwork.{ChargingCycle, ChargingStation, ChargingVehicle}
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.router.skim.event
import beam.sim.BeamServices
import cats.Eval
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id

import scala.collection.mutable

class SitePowerManager(chargingNetworkMap: Map[Id[VehicleManager], ChargingNetwork], beamServices: BeamServices)
    extends LazyLogging {
  import SitePowerManager._

  private val cnmConfig = beamServices.beamConfig.beam.agentsim.chargingNetworkManager
  private lazy val allChargingStations = chargingNetworkMap.flatMap(_._2.chargingStations).toList.distinct
  private val unlimitedPhysicalBounds = getUnlimitedPhysicalBounds(allChargingStations).value
  private val temporaryLoadEstimate = mutable.HashMap.empty[ChargingStation, Double]

  /**
    * Get required power for electrical vehicles
    *
    * @param tick current time
    * @return power (in Kilo Watt) over planning horizon
    */
  def requiredPowerInKWOverNextPlanningHorizon(tick: Int): Map[ChargingStation, PowerInKW] = {
    val plans = allChargingStations.par
      .map(station => station -> temporaryLoadEstimate.getOrElse(station, 0.0))
      .seq
      .toMap
    temporaryLoadEstimate.clear()
    if (plans.isEmpty) logger.error(s"Charging Replan did not produce allocations")
    plans
  }

  /**
    *
    * @param chargingVehicle the vehicle being charging
    * @param physicalBounds physical bounds under which the dispatch occur
    * @return
    */
  def dispatchEnergy(
    timeInterval: Int,
    chargingVehicle: ChargingVehicle,
    physicalBounds: Map[ChargingStation, PhysicalBounds]
  ): (ChargingDurationInSec, EnergyInJoules) = {
    assume(timeInterval >= 0, "timeInterval should not be negative!")
    val ChargingVehicle(vehicle, _, station, _, _, _, _, _) = chargingVehicle
    // dispatch
    val maxZoneLoad = physicalBounds(station).powerLimitUpper
    val maxUnlimitedZoneLoad = unlimitedPhysicalBounds(station).powerLimitUpper
    val chargingPointLoad =
      ChargingPointType.getChargingPointInstalledPowerInKw(station.zone.chargingPointType)
    val chargingPowerLimit = maxZoneLoad * chargingPointLoad / maxUnlimitedZoneLoad
    vehicle.refuelingSessionDurationAndEnergyInJoules(
      sessionDurationLimit = Some(timeInterval),
      stateOfChargeLimit = None,
      chargingPowerLimit = Some(chargingPowerLimit)
    )
  }

  /**
    * Collect rough power demand per vehicle
    * @param time start time of charging cycle
    * @param duration duration of charging cycle
    * @param vehicle vehicle charging
    * @param station the station where vehicle is charging
    */
  def collectObservedLoadInKW(
    time: Int,
    duration: Int,
    vehicle: BeamVehicle,
    station: ChargingStation
  ): Unit = {
    // Collect data on load demand
    val (chargingDuration, requiredEnergy) = vehicle.refuelingSessionDurationAndEnergyInJoules(
      sessionDurationLimit = Some(duration),
      stateOfChargeLimit = None,
      chargingPowerLimit = None
    )
    val requiredLoad = if (chargingDuration == 0) 0.0 else (requiredEnergy / 3.6e+6) / (chargingDuration / 3600.0)
    temporaryLoadEstimate.synchronized {
      val requiredLoadAcc = temporaryLoadEstimate.getOrElse(station, 0.0) + requiredLoad
      temporaryLoadEstimate.put(station, requiredLoadAcc)
    }
    val timeBin = cnmConfig.timeStepInSeconds * (time / cnmConfig.timeStepInSeconds)
    val location = beamServices.beamScenario.tazTreeMap.getTAZ(station.zone.tazId).get.coord
    beamServices.matsimServices.getEvents.processEvent(
      event.TAZSkimmerEvent(timeBin, location, station.zone.id, requiredLoad, beamServices, "CNM")
    )
  }
}

object SitePowerManager {
  type PowerInKW = Double
  type EnergyInJoules = Double
  type ChargingDurationInSec = Int

  case class PhysicalBounds(
    station: ChargingStation,
    powerLimitUpper: PowerInKW,
    powerLimitLower: PowerInKW,
    lpmWithControlSignal: Double
  )

  /**
    * create unlimited physical bounds
    * @param stations sequence of stations for which to produce physical bounds
    * @return map of physical bounds
    */
  def getUnlimitedPhysicalBounds(stations: Seq[ChargingStation]): Eval[Map[ChargingStation, PhysicalBounds]] = {
    Eval.later {
      stations.map {
        case station @ ChargingStation(zone) =>
          station -> PhysicalBounds(
            station,
            ChargingPointType.getChargingPointInstalledPowerInKw(zone.chargingPointType) * zone.numChargers,
            ChargingPointType.getChargingPointInstalledPowerInKw(zone.chargingPointType) * zone.numChargers,
            0.0
          )
      }.toMap
    }
  }
}
