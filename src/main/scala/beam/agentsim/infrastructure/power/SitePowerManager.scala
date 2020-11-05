package beam.agentsim.infrastructure.power

import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.infrastructure.ChargingNetwork
import beam.agentsim.infrastructure.ChargingNetwork.{ChargingStation, ChargingVehicle}
import beam.agentsim.infrastructure.ChargingNetworkManager._
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.router.skim.event
import beam.sim.BeamServices
import cats.Eval
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, TimeoutException}

class SitePowerManager(chargingNetworkMap: TrieMap[String, ChargingNetwork], beamServices: BeamServices)
    extends LazyLogging {
  import SitePowerManager._

  private val cnmConfig = beamServices.beamConfig.beam.agentsim.chargingNetworkManager
  private val tazSkimmer = beamServices.skims.taz_skimmer

  private val allChargingStations = chargingNetworkMap.flatMap(_._2.chargingStationsMap.values).toList.distinct
  private val unlimitedPhysicalBounds = getUnlimitedPhysicalBounds(allChargingStations).value

  /**
    * Get required power for electrical vehicles
    *
    * @param tick bean time
    * @return power (in Kilo Watt) over planning horizon
    */
  def getPowerOverNextPlanningHorizon(tick: Int): Map[ChargingStation, PowerInKW] = {
    lazy val planningFuture = Future
      .sequence(allChargingStations.map { station =>
        Future {
          val estimatedLoad = getPowerFromSkim(tick, station.zone) match {
            case None       => estimatePowerDemand(tick, station.zone)
            case Some(load) => load
          }
          station -> estimatedLoad
        }
      })
      .map(_.toMap)
      .recover {
        case e =>
          logger.warn(s"Charging Replan did not produce allocations: $e")
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
    * @return
    */
  private def getPowerFromSkim(tick: Int, zone: ChargingZone): Option[Double] = {
    if (!tazSkimmer.isLatestSkimEmpty) {
      val currentTimeBin = cnmConfig.timeStepInSeconds * (tick / cnmConfig.timeStepInSeconds)
      beamServices.skims.taz_skimmer.getLatestSkim(
        currentTimeBin,
        zone.tazId,
        "CNM",
        zone.uniqueId
      ) match {
        case Some(skim) => Some(skim.value * skim.observations)
        case None       => Some(0.0)
      }
    } else {
      None
    }
  }

  /**
    * get estimated power from current partial skim
    * @param tick timeBin
    * @param zone the Charging Zone
    * @return
    */
  private def estimatePowerDemand(tick: Int, zone: ChargingZone): Double = {
    val previousTimeBin = cnmConfig.timeStepInSeconds * ((tick / cnmConfig.timeStepInSeconds) - 1)
    tazSkimmer.getPartialSkim(previousTimeBin, zone.tazId, "CNM", zone.vehicleManager + "-" + zone.chargingZoneId) match {
      case Some(skim) => skim.value * skim.observations
      case None       => 0.0
    }
  }

  /**
    * Wait for futureChargingReplanPerVehicleBis to terminate before returning the charging plans per vehicle
    *
    * @param chargingSessionLimiterInSec duration of charging
    * @return map of electrical vehicles with required amount of energy in joules
    */
  def dispatchEnergy(
    tick: Int,
    physicalBounds: Map[ChargingStation, PhysicalBounds],
    chargingSessionLimiterInSec: Option[Int] = None
  ): Map[Id[BeamVehicle], (ChargingNetwork, ChargingDurationInSec, EnergyInJoules)] = {
    val timeInterval =
      chargingSessionLimiterInSec.map(Math.min(cnmConfig.timeStepInSeconds, _)).getOrElse(cnmConfig.timeStepInSeconds)
    val vehicles =
      chargingNetworkMap
        .flatMap(x => x._2.chargingStationsMap.map(_._2 -> x._2))
        .flatMap(y => y._1.connectedVehicles.map(_ -> y))
    val future = Future
      .sequence(
        vehicles.map {
          case (v, (s, cn)) =>
            Future {
              val maxZoneLoad = physicalBounds(s).maxLoad
              val maxUnlimitedZoneLoad = unlimitedPhysicalBounds(s).maxLoad
              val chargingPointLoad = ChargingPointType.getChargingPointInstalledPowerInKw(s.zone.chargingPointType)
              val chargingPowerLimit = maxZoneLoad * chargingPointLoad / maxUnlimitedZoneLoad
              val (chargingDuration, energyToCharge) =
                v.refuelingSessionDurationAndEnergyInJoules(Some(timeInterval), Some(chargingPowerLimit))
              val chargingVehicle = cn.lookupVehicle(v.id).get
              collectDataOnLoadDemand(tick, chargingVehicle, chargingDuration)
              v.id -> (cn, chargingDuration, energyToCharge)
            }
        }
      )
      .map(result => result.toMap)
      .recover {
        case e =>
          logger.warn(s"Charging Replan did not produce allocations: $e")
          Map.empty[Id[BeamVehicle], (ChargingNetwork, ChargingDurationInSec, EnergyInJoules)]
      }
    try {
      Await.result(future, atMost = 1.minutes)
    } catch {
      case e: TimeoutException =>
        logger.error(s"timeout of Charging Replan with no allocations made: $e")
        Map.empty[Id[BeamVehicle], (ChargingNetwork, ChargingDurationInSec, EnergyInJoules)]
    }
  }

  /**
    * Collect rough power demand per vehicle
    * @param tick current time
    * @param chargingVehicle vehicle that needs to be recharged
    * @param maxChargingDuration maximum Charging duration for the current session
    */
  private def collectDataOnLoadDemand(
    tick: Int,
    chargingVehicle: ChargingVehicle,
    maxChargingDuration: Long
  ): Unit = {
    // Collect data on load demand
    val currentBin = cnmConfig.timeStepInSeconds * (tick / cnmConfig.timeStepInSeconds)
    val veh = chargingVehicle.vehicle
    val (chargingDuration, requiredEnergy) = veh.refuelingSessionDurationAndEnergyInJoules(Some(maxChargingDuration))
    val chargingDurationBis = Math.min(chargingDuration, maxChargingDuration)
    beamServices.matsimServices.getEvents.processEvent(
      event.TAZSkimmerEvent(
        currentBin,
        veh.stall.get.locationUTM,
        chargingVehicle.chargingStation.zone.uniqueId,
        if (chargingDurationBis == 0) 0.0 else (requiredEnergy / 3.6e+6) / (chargingDurationBis / 3600.0),
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
