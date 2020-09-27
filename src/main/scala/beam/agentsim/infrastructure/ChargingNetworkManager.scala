package beam.agentsim.infrastructure

import akka.actor.{Actor, ActorLogging, ActorRef}
import beam.agentsim.agents.modalbehaviors.DrivesVehicle._
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.{ParkingType, PricingModel}
import beam.agentsim.infrastructure.power.{PowerController, SitePowerManager}
import beam.agentsim.infrastructure.taz.TAZ
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.BeamRouter.Location
import beam.sim.config.BeamConfig
import beam.sim.{BeamScenario, BeamServices}
import beam.utils.DateUtils
import beam.utils.matsim_conversion.ShapeUtils.QuadTreeBounds
import org.matsim.api.core.v01.Id
import org.matsim.core.utils.collections.QuadTree

import scala.collection.concurrent.TrieMap
import scala.util.Random

import scala.collection.JavaConverters._

class ChargingNetworkManager(
  beamServices: BeamServices,
  beamScenario: BeamScenario,
  scheduler: ActorRef,
  activityQuadTreeBounds: QuadTreeBounds
) extends Actor
    with ActorLogging {
  import ChargingNetworkManager._

  private val beamConfig: BeamConfig = beamScenario.beamConfig
  private val cnmConfig = beamConfig.beam.agentsim.chargingNetworkManager
  private val vehiclesToCharge: TrieMap[Id[BeamVehicle], ChargingVehicle] = new TrieMap()
  private def vehicles: Map[Id[BeamVehicle], BeamVehicle] = vehiclesToCharge.mapValues(_.vehicle).toMap

  private val chargingStationsQTree: QuadTree[ChargingZone] = loadChargingStations()
  private val sitePowerManager = new SitePowerManager(chargingStationsQTree.values().asScala.map(s => s.parkingZoneId -> s).toMap, beamServices)
  private val powerController = new PowerController(beamServices, beamConfig)
  private val endOfSimulationTime: Int = DateUtils.getEndOfTime(beamConfig)



  log.info("ChargingNetworkManager should be connected to grid: {}", cnmConfig.gridConnectionEnabled)
  if (cnmConfig.gridConnectionEnabled) {
    log.info("ChargingNetworkManager is connected to grid: {}", powerController.isConnectedToGrid)
  }

  override def receive: Receive = {
    case ChargingPlugRequest(vehicle, drivingAgent) =>
      if (vehicle.isBEV | vehicle.isPHEV) {
        log.info(
          "ChargingPlugRequest for vehicle {} by agent {} on stall {}",
          vehicle,
          drivingAgent.path.name,
          vehicle.stall
        )
        vehiclesToCharge.put(
          vehicle.id,
          ChargingVehicle(
            vehicle,
            drivingAgent,
            totalChargingSession = ChargingSession.Empty,
            lastChargingSession = ChargingSession.Empty
          )
        )
      } else {
        log.error(
          "ChargingPlugRequest for non BEV/PHEV vehicle {} by agent {} on stall {}",
          vehicle,
          drivingAgent.path.name,
          vehicle.stall
        )
      }

    case ChargingUnplugRequest(vehicle, tick) =>
      log.info("ChargingUnplugRequest for vehicle {} at {}", vehicle, tick)

      vehiclesToCharge
        .remove(vehicle.id)
        .map { cv =>
          val chargeDuration = tick % cnmConfig.chargingSessionInSeconds
          val (chargeDurationByTick, energyByTick) =
            vehicle.refuelingSessionDurationAndEnergyInJoules(Some(chargeDuration))
          vehicle.addFuel(energyByTick)
          val currentSession = ChargingSession(energyByTick, chargeDurationByTick)
          val newTotalSession = cv.totalChargingSession.combine(currentSession)
          log.debug(
            "Vehicle {} is removed from ChargingManager. Scheduling EndRefuelSessionTrigger at {} with {} J delivered",
            vehicle,
            tick,
            newTotalSession.energy
          )
          scheduler ! ScheduleTrigger(
            EndRefuelSessionTrigger(tick, vehicle.getChargerConnectedTick(), newTotalSession.energy, vehicle),
            cv.agent
          )
        }

    case TriggerWithId(PlanningTimeOutTrigger(tick), triggerId) =>
      // replanning for the next horizon
      log.debug("PlanningTimeOutTrigger, tick: {}", tick)
      sitePowerManager.updatePhysicalBounds(
        if (cnmConfig.gridConnectionEnabled) {
          powerController.obtainPowerPhysicalBounds(tick, sitePowerManager.getPowerOverNextPlanningHorizon(tick))
        } else {
          powerController.defaultPowerPhysicalBounds(tick, sitePowerManager.getPowerOverNextPlanningHorizon(tick))
        }
      )
      if (tick == 0) sender ! ScheduleTrigger(ChargingTimeOutTrigger(cnmConfig.chargingSessionInSeconds), self)
      val nextTick = cnmConfig.planningHorizonInSeconds * (1 + (tick / cnmConfig.planningHorizonInSeconds))
      sender ! CompletionNotice(
        triggerId,
        if (nextTick <= endOfSimulationTime)
          Vector(ScheduleTrigger(PlanningTimeOutTrigger(nextTick), self))
        else
          Vector.empty[ScheduleTrigger]
      )

    case TriggerWithId(ChargingTimeOutTrigger(tick), triggerId) =>
      if (vehiclesToCharge.nonEmpty)
        log.debug("ChargingTimeOutTrigger, tick: {}", tick)

      val requiredEnergyPerVehicle = sitePowerManager.replanHorizonAndGetChargingPlanPerVehicle(tick, vehicles)
      val maxChargeDuration = cnmConfig.chargingSessionInSeconds

      val scheduleTriggers = requiredEnergyPerVehicle.flatMap {
        case (vehicleId, requiredEnergy) if requiredEnergy > 0 =>
          val ChargingVehicle(vehicle, agent, totalChargingSession, _) = vehiclesToCharge(vehicleId)

          log.debug("Charging vehicle {}. Required energy = {}", vehicle, requiredEnergy)

          val (chargingDuration, providedEnergy) =
            vehicle.refuelingSessionDurationAndEnergyInJoules(Some(maxChargeDuration))

          vehicle.addFuel(providedEnergy)
          val currentSession = ChargingSession(providedEnergy, chargingDuration)
          val newTotalSession = totalChargingSession.combine(currentSession)

          endRefuelSessionTriggerMaybe(
            vehicle,
            tick,
            maxChargeDuration,
            currentSession,
            newTotalSession
          ).map { endRefuelSession =>
              vehiclesToCharge.remove(vehicleId)
              ScheduleTrigger(endRefuelSession, agent)
            }
            .orElse {
              vehiclesToCharge.update(vehicleId, ChargingVehicle(vehicle, agent, newTotalSession, currentSession))
              None
            }

        case (id, energy) if energy <= 0 =>
          log.warning(
            "Vehicle {}  (primaryFuelLevel = {}) requires energy {} - which is less or equals zero",
            vehicles(id),
            vehicles(id).primaryFuelLevelInJoules,
            energy
          )
          None
      }.toVector

      val nextTick = cnmConfig.chargingSessionInSeconds * (1 + (tick / cnmConfig.chargingSessionInSeconds))
      sender ! CompletionNotice(
        triggerId,
        if (nextTick <= endOfSimulationTime)
          scheduleTriggers :+ ScheduleTrigger(ChargingTimeOutTrigger(nextTick), self)
        else {
          // if we still have a BEV/PHEV that is connected to a charging point,
          // we assume that they will charge until the end of the simulation and throwing events accordingly
          val completeTriggers = scheduleTriggers ++ vehiclesToCharge.map {
            case (_, cv) =>
              ScheduleTrigger(
                EndRefuelSessionTrigger(
                  tick,
                  cv.vehicle.getChargerConnectedTick(),
                  cv.totalChargingSession.energy,
                  cv.vehicle
                ),
                cv.agent
              )
          }
          vehiclesToCharge.clear()
          completeTriggers
        }
      )
  }

  private def endRefuelSessionTriggerMaybe(
    vehicle: BeamVehicle,
    tick: Int,
    maxChargeDuration: Long,
    currentSession: ChargingSession,
    totalSession: ChargingSession
  ): Option[EndRefuelSessionTrigger] = {
    if (vehicle.primaryFuelLevelInJoules >= vehicle.beamVehicleType.primaryFuelCapacityInJoule) {
      log.debug(
        "Vehicle {} is fully charged. Scheduling EndRefuelSessionTrigger at {} with {} J delivered",
        vehicle.id,
        tick + currentSession.duration.toInt,
        totalSession.energy
      )
      Some(
        EndRefuelSessionTrigger(
          tick + currentSession.duration.toInt,
          vehicle.getChargerConnectedTick(),
          totalSession.energy,
          vehicle
        )
      )
    } else if (currentSession.duration < maxChargeDuration) {
      log.debug(
        "Vehicle {} is charged by a short time: {}. Scheduling EndRefuelSessionTrigger at {} with {} J delivered",
        vehicle.id,
        currentSession.duration,
        tick + currentSession.duration.toInt,
        totalSession.energy
      )
      Some(
        EndRefuelSessionTrigger(
          tick + currentSession.duration.toInt,
          vehicle.getChargerConnectedTick(),
          totalSession.energy,
          vehicle
        )
      )
    } else {
      log.debug(
        "Ending refuel cycle for vehicle {}. Provided {} J. during {}",
        vehicle.id,
        currentSession.energy,
        currentSession.duration
      )
      None
    }
  }

  private def loadChargingStations(): QuadTree[ChargingZone] = {
    val (zones, _) = ZonalParkingManager.loadParkingZones(
      beamConfig.beam.agentsim.taz.parkingFilePath,
      beamConfig.beam.agentsim.taz.filePath,
      beamConfig.beam.agentsim.taz.parkingStallCountScalingFactor,
      beamConfig.beam.agentsim.taz.parkingCostScalingFactor,
      new Random(beamConfig.matsim.modules.global.randomSeed)
    )
    val stationsQuadTree: QuadTree[ChargingZone] = new QuadTree[ChargingZone](
      activityQuadTreeBounds.minx,
      activityQuadTreeBounds.miny,
      activityQuadTreeBounds.maxx,
      activityQuadTreeBounds.maxy
    )
    zones.filter(_.chargingPointType.isDefined).foreach { zone =>
      beamScenario.tazTreeMap.getTAZ(zone.tazId).foreach { taz =>
        stationsQuadTree.put(
          taz.coord.getX,
          taz.coord.getY,
          ChargingZone(
            zone.parkingZoneId,
            zone.tazId,
            zone.parkingType,
            zone.stallsAvailable,
            zone.maxStalls,
            zone.chargingPointType.get,
            zone.pricingModel.get
          )
        )
      }
    }
    stationsQuadTree
  }

  override def postStop: Unit = {
    log.info("postStop")
    if (cnmConfig.gridConnectionEnabled) {
      powerController.close()
    }
    super.postStop()
  }
}

object ChargingNetworkManager {
  final case class PlanningTimeOutTrigger(tick: Int) extends Trigger
  final case class ChargingTimeOutTrigger(tick: Int) extends Trigger

  final case class ChargingSession(energy: Double, duration: Long) {

    def combine(other: ChargingSession): ChargingSession = ChargingSession(
      energy = this.energy + other.energy,
      duration = this.duration + other.duration
    )
  }
  final case class ChargingZone(
    parkingZoneId: Int,
    tazId: Id[TAZ],
    parkingType: ParkingType,
    stationsAvailable: Int,
    maxStations: Int,
    chargingPointType: ChargingPointType,
    pricingModel: PricingModel
  )
  final case class ChargingStation(zone: ChargingZone, locationUTM: Location, costInDollars: Double)
  final case class ChargingVehicle(
    vehicle: BeamVehicle,
    agent: ActorRef,
    totalChargingSession: ChargingSession,
    lastChargingSession: ChargingSession
  )

  object ChargingSession {
    val Empty: ChargingSession = ChargingSession(0.0, 0)
  }

}
