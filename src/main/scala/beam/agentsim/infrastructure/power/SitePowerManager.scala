package beam.agentsim.infrastructure.power

import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.infrastructure.ChargingNetworkManager
import beam.agentsim.infrastructure.ChargingNetworkManager._
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.router.skim.TAZSkims
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, TimeoutException}

class SitePowerManager(chargingStations: Map[Int, ChargingZone], planningHorizonInSec: Int, tazSkimmer: TAZSkims)
    extends LazyLogging {

  val unlimitedPhysicalBounds: Map[ZoneId, PhysicalBounds] =
    ChargingNetworkManager.unlimitedPhysicalBounds(chargingStations).value

  /**
    * Get required power for electrical vehicles
    *
    * @param tick bean time
    * @return power (in Kilo Watt) over planning horizon
    */
  def getPowerOverNextPlanningHorizon(tick: Int): Map[Int, PowerInKW] = {
    chargingStations.map {
      case (_, zone) =>
        val estimatedLoad = getPowerFromSkim(tick, zone) match {
          case None       => estimatePowerDemand(tick, zone)
          case Some(load) => load
        }
        zone.parkingZoneId -> estimatedLoad
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
      val currentTimeBin = planningHorizonInSec * (tick / planningHorizonInSec)
      tazSkimmer.getLatestSkim(currentTimeBin, zone.tazId, SKIM_ACTOR, SKIM_VAR_PREFIX + zone.parkingZoneId) match {
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
    val previousTimeBin = planningHorizonInSec * ((tick / planningHorizonInSec) - 1)
    tazSkimmer.getPartialSkim(previousTimeBin, zone.tazId, SKIM_ACTOR, SKIM_VAR_PREFIX + zone.parkingZoneId) match {
      case Some(skim) => skim.value * skim.observations
      case None       => 0.0
    }
  }

  /**
    * Replan horizon per electrical vehicles
    *
    * @param vehicles beam vehicles
    * @param chargingSessionInSec duration of charging
    * @return a future of map of electrical vehicles with required amount of energy in joules
    */
  private def replanHorizonAndGetChargingPlanPerVehicleHelper(
    vehicles: Iterable[BeamVehicle],
    physicalBounds: Map[Int, PhysicalBounds],
    chargingSessionInSec: Int
  ): Future[Map[Id[BeamVehicle], (ChargingDurationInSec, EnergyInJoules, EnergyInJoules)]] = {
    val timeInterval = Math.min(planningHorizonInSec, chargingSessionInSec)
    Future
      .sequence(
        vehicles.map { v =>
          Future {
            val stall = v.stall.get
            val maxZoneLoad = physicalBounds(stall.parkingZoneId).maxLoad
            val maxUnlimitedZoneLoad = unlimitedPhysicalBounds(stall.parkingZoneId).maxLoad
            val chargingPointLoad = ChargingPointType.getChargingPointInstalledPowerInKw(stall.chargingPointType.get)
            val chargingPowerLimit = maxZoneLoad * chargingPointLoad / maxUnlimitedZoneLoad
            val (chargingDuration, energyToCharge) =
              v.refuelingSessionDurationAndEnergyInJoules(Some(timeInterval), Some(chargingPowerLimit))
            val (_, unconstrainedEnergy) = v.refuelingSessionDurationAndEnergyInJoules(Some(timeInterval))
            v.id -> (chargingDuration, energyToCharge, unconstrainedEnergy)
          }
        }
      )
      .map(result => result.toMap)
      .recover {
        case e =>
          logger.warn(s"Charging Replan did not produce allocations: $e")
          Map.empty[Id[BeamVehicle], (ChargingDurationInSec, EnergyInJoules, EnergyInJoules)]
      }
  }

  /**
    * Wait for futureChargingReplanPerVehicleBis to terminate before returning the charging plans per vehicle
    *
    * @param vehicles beam vehicles
    * @param chargingSessionInSec duration of charging
    * @return map of electrical vehicles with required amount of energy in joules
    */
  def replanHorizonAndGetChargingPlanPerVehicle(
    vehicles: Iterable[BeamVehicle],
    physicalBounds: Map[Int, PhysicalBounds],
    chargingSessionInSec: Int
  ): Map[Id[BeamVehicle], (ChargingDurationInSec, EnergyInJoules, EnergyInJoules)] = {
    try {
      Await.result(
        replanHorizonAndGetChargingPlanPerVehicleHelper(vehicles, physicalBounds, chargingSessionInSec),
        atMost = 1.minutes
      )
    } catch {
      case e: TimeoutException =>
        logger.error(s"timeout of Charging Replan with no allocations made: $e")
        Map.empty[Id[BeamVehicle], (ChargingDurationInSec, EnergyInJoules, EnergyInJoules)]
    }
  }
}
