package beam.agentsim.infrastructure.power

import beam.agentsim.agents.vehicles.VehicleManager
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
    * @param chargingVehicle vehicle charging information
    * @param chargingSession latest charging sessions to collect
    */
  def collectObservedLoadInKW(chargingVehicle: ChargingVehicle, chargingSession: ChargingCycle): Unit = {
    import chargingSession._
    import chargingVehicle._
    // Collect data on load demand
    val (chargingDuration, requiredEnergy) = vehicle.refuelingSessionDurationAndEnergyInJoules(
      sessionDurationLimit = Some(duration),
      stateOfChargeLimit = None,
      chargingPowerLimit = None
    )
    val requiredLoad = if (chargingDuration == 0) 0.0 else (requiredEnergy / 3.6e+6) / (chargingDuration / 3600.0)
    temporaryLoadEstimate.synchronized {
      val requiredLoadAcc = temporaryLoadEstimate.getOrElse(chargingStation, 0.0) + requiredLoad
      temporaryLoadEstimate.put(chargingStation, requiredLoadAcc)
    }
    beamServices.matsimServices.getEvents.processEvent(
      event.TAZSkimmerEvent(
        cnmConfig.timeStepInSeconds * (startTime / cnmConfig.timeStepInSeconds),
        beamServices.beamScenario.tazTreeMap.getTAZ(chargingStation.zone.tazId).get.coord,
        chargingStation.zone.id,
        requiredLoad,
        beamServices,
        "CNM"
      )
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
