package beam.agentsim.infrastructure

import akka.actor.{Actor, ActorLogging, ActorRef}
import beam.agentsim.agents.modalbehaviors.DrivesVehicle._
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.{ParkingType, PricingModel}
import beam.agentsim.infrastructure.power.SitePowerManager.{SKIM_ACTOR, SKIM_VAR_PREFIX}
import beam.agentsim.infrastructure.power.{PowerController, SitePowerManager}
import beam.agentsim.infrastructure.taz.TAZ
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.BeamRouter.Location
import beam.router.skim.TAZSkimmerEvent
import beam.sim.config.BeamConfig
import beam.sim.{BeamScenario, BeamServices}
import beam.utils.DateUtils
import beam.utils.matsim_conversion.ShapeUtils.QuadTreeBounds
import org.matsim.api.core.v01.Id
import org.matsim.core.utils.collections.QuadTree

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.util.Random

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
  private val sitePowerManager =
    new SitePowerManager(chargingStationsQTree.values().asScala.map(s => s.parkingZoneId -> s).toMap, beamServices)
  private val powerController = new PowerController(beamServices, beamConfig)
  private val endOfSimulationTime: Int = DateUtils.getEndOfTime(beamConfig)

  log.info("ChargingNetworkManager should be connected to grid: {}", cnmConfig.gridConnectionEnabled)
  private val isConnectedToTheGrid: Boolean = cnmConfig.gridConnectionEnabled && powerController.initFederateConnection
  log.info("ChargingNetworkManager is connected to grid: {}", isConnectedToTheGrid)

  override def receive: Receive = {
    case TriggerWithId(PlanningTimeOutTrigger(tick), triggerId) =>
      log.debug("PlanningTimeOutTrigger, tick: {}", tick)
      // Update physical bounds either via the Grid or use the default physical bounds
      sitePowerManager.updatePhysicalBounds(
        if (isConnectedToTheGrid) {
          powerController.obtainPowerPhysicalBounds(tick, sitePowerManager.getPowerOverNextPlanningHorizon(tick))
        } else {
          powerController.defaultPowerPhysicalBounds(tick, sitePowerManager.getPowerOverNextPlanningHorizon(tick))
        }
      )

      // Plan a ChargingTimeOutTrigger. Charging occurs at the end of each charging session.
      // If charging session is 300, then charging occurs at time 300
      // by calculating the energy required from time 0 to 300.
      if (tick == 0)
        sender ! ScheduleTrigger(ChargingTimeOutTrigger(cnmConfig.chargingSessionInSeconds), self)

      // Replan PlanningTimeOutTrigger to update the the SitePowerManager's physical bounds
      val nextTick = cnmConfig.planningHorizonInSeconds * (1 + (tick / cnmConfig.planningHorizonInSeconds))
      sender ! CompletionNotice(
        triggerId,
        if (nextTick <= endOfSimulationTime)
          Vector(ScheduleTrigger(PlanningTimeOutTrigger(nextTick), self))
        else
          Vector.empty[ScheduleTrigger]
      )

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

      // compute charging plan for the vehicle to unplug at time tick
      sitePowerManager
        .replanHorizonAndGetChargingPlanPerVehicle(
          vehicles.values,
          tick % cnmConfig.chargingSessionInSeconds
        )
        .get(vehicle.id) match {
        case Some(chargingPlan) =>
          vehiclesToCharge
            .remove(vehicle.id)
            .map { cv =>
              val (chargeDurationAtTick, constrainedEnergyToCharge, unconstrainedEnergy) = chargingPlan

              // Collect data on unconstrained load demand (not the constrained demand)
              collectDataOnLoadDemand(tick, vehicle, chargeDurationAtTick, unconstrainedEnergy)

              // Refuel the vehicle
              vehicle.addFuel(constrainedEnergyToCharge)

              // Preparing EndRefuelSessionTrigger to notify the driver
              val currentSession = ChargingSession(constrainedEnergyToCharge, chargeDurationAtTick)
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
        case _ => //
      }

    case TriggerWithId(ChargingTimeOutTrigger(tick), triggerId) =>
      if (vehiclesToCharge.nonEmpty)
        log.debug("ChargingTimeOutTrigger, tick: {}", tick)

      // Calculate the energy to charge each vehicle connected to the a charging station
      val scheduleTriggers = sitePowerManager
        .replanHorizonAndGetChargingPlanPerVehicle(vehicles.values, cnmConfig.chargingSessionInSeconds)
        .flatMap {
          case (vehicleId, (chargingDuration, constrainedEnergyToCharge, unconstrainedEnergy)) =>
            // Get vehicle charging status
            val ChargingVehicle(vehicle, agent, totalChargingSession, _) = vehiclesToCharge(vehicleId)

            // Collect data on load demand
            collectDataOnLoadDemand(tick, vehicle, chargingDuration, unconstrainedEnergy)

            // Refuel the vehicle
            log.debug("Charging vehicle {}. Energy to charge = {}", vehicle, constrainedEnergyToCharge)
            vehicle.addFuel(constrainedEnergyToCharge)

            // Verify the state of charge
            val currentSession = ChargingSession(constrainedEnergyToCharge, chargingDuration)
            val totalSession = totalChargingSession.combine(currentSession)
            if (endRefuelSessionTriggerMaybe(vehicle, tick, currentSession, totalSession)) {
              vehiclesToCharge.remove(vehicle.id)
              Some(
                ScheduleTrigger(
                  EndRefuelSessionTrigger(
                    tick + currentSession.duration.toInt,
                    vehicle.getChargerConnectedTick(),
                    totalSession.energy,
                    vehicle
                  ),
                  agent
                )
              )
            } else {
              vehiclesToCharge.update(vehicle.id, ChargingVehicle(vehicle, agent, totalSession, currentSession))
              None
            }
        }
        .toVector

      // Preparing EndRefuelSessionTrigger to notify the drivers and replanning the ChargingTimeOutTrigger
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

  private def collectDataOnLoadDemand(
    tick: Int,
    vehicle: BeamVehicle,
    chargingDuration: Long,
    requiredEnergy: Double
  ): Unit = {
    // Collect data on load demand
    beamServices.matsimServices.getEvents.processEvent(
      TAZSkimmerEvent(
        tick,
        vehicle.stall.get.locationUTM,
        SKIM_VAR_PREFIX + vehicle,
        (requiredEnergy / 3.6e+6) / (chargingDuration / 3600.0),
        beamServices,
        SKIM_ACTOR
      )
    )
  }

  private def endRefuelSessionTriggerMaybe(
    vehicle: BeamVehicle,
    tick: Int,
    currentSession: ChargingSession,
    totalSession: ChargingSession
  ): Boolean = {
    vehicle.refuelingSessionDurationAndEnergyInJoules() match {
      case (chargingDuration, energyRequired) if chargingDuration == 0 && energyRequired == 0.0 =>
        log.debug(
          "Vehicle {} is fully charged. Scheduling EndRefuelSessionTrigger at {} with {} J delivered",
          vehicle.id,
          tick + currentSession.duration.toInt,
          totalSession.energy
        )
        true
      case (chargingDuration, energyRequired) =>
        log.debug(
          "Ending refuel cycle for vehicle {}. Provided {} J. remaining {} J for {} sec",
          vehicle.id,
          currentSession.energy,
          energyRequired,
          chargingDuration
        )
        false
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
      beamScenario.tazTreeMap.getTAZ(zone.tazId) match {
        case Some(taz) =>
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
        case _ =>
      }
    }
    stationsQuadTree
  }

  override def postStop: Unit = {
    log.info("postStop")
    if (cnmConfig.gridConnectionEnabled) {
      powerController.close()
    }
    sitePowerManager.resetState()
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
