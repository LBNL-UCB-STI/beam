package beam.agentsim.infrastructure.power

import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.infrastructure.ChargingNetworkManager.ChargingZone
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.router.skim.{TAZSkimmerEvent, TAZSkims}
import beam.sim.BeamServices
import org.matsim.api.core.v01.Id
import org.matsim.core.api.experimental.events.EventsManager

class SitePowerManager(chargingStations: Map[Int, ChargingZone], beamServices: BeamServices) {
  import SitePowerManager._

  val tazSkimmer: TAZSkims = beamServices.skims.taz_skimmer
  val eventsManager: EventsManager = beamServices.matsimServices.getEvents
  val planningHorizonInSeconds = beamServices.beamConfig.beam.agentsim.chargingNetworkManager.planningHorizonInSeconds
  private var physicalBounds: Map[Int, PhysicalBounds] = chargingStations.map(s => s._1 -> PhysicalBounds())

  /**
    * Set physical bounds
    *
    * @param physicalBounds Physical bounds from the Power Controller
    */
  def updatePhysicalBounds(physicalBounds: Map[Int, PhysicalBounds]) = {
    this.physicalBounds = physicalBounds
  }

  /**
    * Get required power for electrical vehicles
    *
    * @param tick bean time
    * @return power (in Kilo Watt) over planning horizon
    */
  def getPowerOverNextPlanningHorizon(tick: Int): Map[ChargingZone, Double] = {
    chargingStations.map {
      case (_, s) =>
        val load = tazSkimmer.getLatestSkimByTAZ(tick, s.tazId, SKIM_ACTOR, SKIM_VAR_PREFIX + s.parkingZoneId) match {
          case Some(skim) => skim.value * skim.observations
          case None       => ChargingPointType.getChargingPointInstalledPowerInKw(s.chargingPointType) * s.maxStations
        }
        s -> load
    }
  }

  /**
    * Replans horizon per electrical vehicles
    *
    * @param vehicles beam vehicles
    * @return map of electrical vehicles with required amount of energy in joules
    */
  def replanHorizonAndGetChargingPlanPerVehicle(
    tick: Int,
    vehicles: Map[Id[BeamVehicle], BeamVehicle]
  ): Map[Id[BeamVehicle], Double] = {
    val vehicleToLoad = vehicles.view
      .map {
        case (_, v) =>
          val load = v
            .refuelingSessionDurationAndEnergyInJoules(Some(planningHorizonInSeconds))
            ._2 / 3600000 / planningHorizonInSeconds
          val zone = v.stall.get.parkingZoneId
          val vehicle = v.id
          eventsManager.processEvent(
            TAZSkimmerEvent(tick, v.stall.get.locationUTM, SKIM_VAR_PREFIX + zone, load, beamServices, SKIM_ACTOR)
          )
          (vehicle, zone, load)
      }
    val powerPerChargingZone = vehicleToLoad.groupBy(_._2).mapValues(_.map(_._2).sum)
    vehicleToLoad.map {
      case (vehicle, zone, load) =>
        (vehicle, Math.min(physicalBounds(zone).maxPower * load / powerPerChargingZone(zone), load))
    }.toMap
  }
}

object SitePowerManager {
  case class PhysicalBounds(minPower: Double = Int.MaxValue, maxPower: Double = Int.MaxValue)
  val SKIM_ACTOR = "SitePowerManager"
  val SKIM_VAR_PREFIX = "ChargingStation-"
}
