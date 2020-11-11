package beam.agentsim.infrastructure.power

import beam.agentsim.infrastructure.ChargingNetwork
import beam.agentsim.infrastructure.ChargingNetwork.{ChargingCycle, ChargingStation, ChargingVehicle}
import beam.agentsim.infrastructure.ChargingNetworkManager.ChargingZone
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.router.skim.event
import beam.sim.BeamServices
import cats.Eval
import com.typesafe.scalalogging.LazyLogging

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, TimeoutException}

class SitePowerManager(chargingNetworkMap: TrieMap[String, ChargingNetwork], beamServices: BeamServices)
    extends LazyLogging {
  import SitePowerManager._

  private val cnmConfig = beamServices.beamConfig.beam.agentsim.chargingNetworkManager
  private val tazSkimmer = beamServices.skims.taz_skimmer
  private val allChargingStations = chargingNetworkMap.flatMap(_._2.lookupStations).toList.distinct
  private val unlimitedPhysicalBounds = getUnlimitedPhysicalBounds(allChargingStations).value

  /**
    * Get required power for electrical vehicles
    *
    * @param tick current time
    * @return power (in Kilo Watt) over planning horizon
    */
  def requiredPowerInKWOverNextPlanningHorizon(tick: Int): Map[ChargingStation, PowerInKW] = {
    lazy val planningFuture = Future
      .sequence(allChargingStations.map { station =>
        Future {
          station -> observedPowerDemandInKW(tick, station.zone).getOrElse(estimatePowerDemandInKW(tick, station.zone))
        }
      })
      .map(_.toMap)
      .recover {
        case e =>
          logger.debug(s"Charging Replan did not produce allocations: $e")
          Map.empty[ChargingStation, PowerInKW]
      }
    try {
      Await.result(planningFuture, atMost = 1.minutes)
    } catch {
      case e: TimeoutException =>
        logger.error(s"timeout of Charging Replan with no allocations made: $e")
        Map.empty[ChargingStation, PowerInKW]
    }
  }

  /**
    * get observed power from previous iteration
    * @param tick timeBin
    * @param zone the Charging Zone
    * @return power in KW
    */
  private def observedPowerDemandInKW(tick: Int, zone: ChargingZone): Option[Double] = {
    if (!tazSkimmer.isLatestSkimEmpty) {
      val currentTimeBin = cnmConfig.timeStepInSeconds * (tick / cnmConfig.timeStepInSeconds)
      beamServices.skims.taz_skimmer.getLatestSkim(currentTimeBin, zone.tazId, "CNM", zone.uniqueId) match {
        case Some(skim) => Some(skim.value * skim.observations)
        case None       => Some(0.0)
      }
    } else None
  }

  /**
    * get estimated power from current partial skim
    * @param tick timeBin
    * @param chargingZone the Charging Zone
    * @return Power in kW
    */
  private def estimatePowerDemandInKW(tick: Int, chargingZone: ChargingZone): Double = {
    val previousTimeBin = cnmConfig.timeStepInSeconds * ((tick / cnmConfig.timeStepInSeconds) - 1)
    val ChargingZone(chargingZoneId, tazId, _, _, _, _, vehicleManager) = chargingZone
    tazSkimmer.getPartialSkim(previousTimeBin, tazId, "CNM", vehicleManager + "-" + chargingZoneId) match {
      case Some(skim) => skim.value * skim.observations
      case None       => 0.0
    }
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
    val ChargingVehicle(vehicle, _, _, station, _, _) = chargingVehicle
    // dispatch
    val maxZoneLoad = physicalBounds(station).maxLoad
    val maxUnlimitedZoneLoad = unlimitedPhysicalBounds(station).maxLoad
    val chargingPointLoad =
      ChargingPointType.getChargingPointInstalledPowerInKw(station.zone.chargingPointType)
    val chargingPowerLimit = maxZoneLoad * chargingPointLoad / maxUnlimitedZoneLoad
    vehicle.refuelingSessionDurationAndEnergyInJoules(Some(timeInterval), Some(chargingPowerLimit))
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
    val (chargingDuration, requiredEnergy) = vehicle.refuelingSessionDurationAndEnergyInJoules(Some(duration))
    beamServices.matsimServices.getEvents.processEvent(
      event.TAZSkimmerEvent(
        cnmConfig.timeStepInSeconds * (startTime / cnmConfig.timeStepInSeconds),
        stall.locationUTM,
        chargingStation.zone.uniqueId,
        if (chargingDuration == 0) 0.0 else (requiredEnergy / 3.6e+6) / (chargingDuration / 3600.0),
        beamServices,
        "CNM"
      )
    )
  }
}

object SitePowerManager {
  type PowerInKW = Double
  type EnergyInJoules = Double
  type ChargingDurationInSec = Long

  case class PhysicalBounds(station: ChargingStation, maxLoad: PowerInKW)

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
            ChargingPointType.getChargingPointInstalledPowerInKw(zone.chargingPointType) * zone.numChargers
          )
      }.toMap
    }
  }
}
