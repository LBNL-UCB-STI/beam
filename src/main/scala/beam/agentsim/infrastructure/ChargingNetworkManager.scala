package beam.agentsim.infrastructure

import akka.actor.{Actor, ActorLogging, ActorRef}
import beam.agentsim.agents.modalbehaviors.DrivesVehicle._
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.infrastructure.power.{PowerController, SitePowerManager}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.sim.config.BeamConfig
import beam.sim.{BeamScenario, BeamServices}
import beam.utils.DateUtils
import org.matsim.api.core.v01.Id

import scala.collection.concurrent.TrieMap

class ChargingNetworkManager(
  beamServices: BeamServices,
  beamScenario: BeamScenario,
  scheduler: ActorRef
) extends Actor
    with ActorLogging {
  import ChargingNetworkManager._

  private val beamConfig: BeamConfig = beamScenario.beamConfig
  private val vehiclesToCharge: TrieMap[Id[BeamVehicle], ChargingVehicle] = new TrieMap()
  private def vehicles: Map[Id[BeamVehicle], BeamVehicle] = vehiclesToCharge.mapValues(_.vehicle).toMap

  private val sitePowerManager = new SitePowerManager()
  private val powerController = new PowerController(beamServices, beamConfig)
  private val endOfSimulationTime: Int = DateUtils.getEndOfTime(beamConfig)

  private val gridConnectionEnabled = beamConfig.beam.agentsim.chargingNetworkManager.gridConnectionEnabled
  log.info("ChargingNetworkManager should be connected to grid: {}", gridConnectionEnabled)
  if (gridConnectionEnabled) {
    val isConnectionEstablished = powerController.initFederateConnection
    log.info("ChargingNetworkManager is actually connected to grid: {}", isConnectionEstablished)
  }

  override def receive: Receive = {
    case ChargingPlugRequest(vehicle, drivingAgent) =>
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

    case ChargingUnplugRequest(vehicle, tick) =>
      log.info("ChargingUnplugRequest for vehicle {} at {}", vehicle, tick)

      vehiclesToCharge
        .remove(vehicle.id)
        .map { cv =>
          val restChargeDuration = (tick % beamConfig.beam.cosim.helics.timeStep)
          val totalEnergyAdjustedByTick =
            if (cv.lastChargingSession.duration == 0) {
              val (_, energy) = vehicle.refuelingSessionDurationAndEnergyInJoules(Some(restChargeDuration))
              energy
            } else {
              val energyAdjustedByLastTick = cv.lastChargingSession.energy / cv.lastChargingSession.duration * restChargeDuration
              (cv.totalChargingSession.energy - cv.lastChargingSession.energy + Math.round(energyAdjustedByLastTick))
            }
          log.debug(
            "Vehicle {} is removed from ChargingManager. Scheduling EndRefuelSessionTrigger at {} with {} J delivered",
            vehicle,
            tick,
            totalEnergyAdjustedByTick
          )
          scheduler ! ScheduleTrigger(
            EndRefuelSessionTrigger(tick, vehicle.getChargerConnectedTick(), totalEnergyAdjustedByTick, vehicle),
            cv.agent
          )
        }

    case TriggerWithId(PlanningTimeOutTrigger(tick), triggerId) =>
      if (vehiclesToCharge.nonEmpty)
        log.debug("PlanningTimeOutTrigger, tick: {}", tick)

      val (nextTick, requiredEnergyPerVehicle) = replanHorizon(tick)
      val maxChargeDuration = nextTick - tick

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

      sender ! CompletionNotice(
        triggerId,
        if (tick < endOfSimulationTime)
          scheduleTriggers :+ ScheduleTrigger(PlanningTimeOutTrigger(nextTick), self)
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

  private def replanHorizon(tick: Int): (Int, Map[Id[BeamVehicle], Double]) = {
    val requiredPower = sitePowerManager.getPowerOverPlanningHorizon(vehicles)

    val (bounds, nextTick) = if (gridConnectionEnabled) {
      powerController.publishPowerOverPlanningHorizon(requiredPower, tick)
      powerController.obtainPowerPhysicalBounds(tick)
    } else {
      powerController.defaultPowerPhysicalBounds(tick)
    }
    val requiredEnergyPerVehicle = sitePowerManager.replanHorizonAndGetChargingPlanPerVehicle(bounds, vehicles)

    if (requiredEnergyPerVehicle.nonEmpty)
      log.info("Required energy per vehicle: {}", requiredEnergyPerVehicle.mkString(","))

    (nextTick, requiredEnergyPerVehicle)
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

  override def postStop: Unit = {
    log.info("postStop")
    if (gridConnectionEnabled) {
      powerController.close()
    }
    super.postStop()
  }
}

object ChargingNetworkManager {
  final case class PlanningTimeOutTrigger(tick: Int) extends Trigger
  final case class ChargingSession(energy: Double, duration: Long) {

    def combine(other: ChargingSession): ChargingSession = ChargingSession(
      energy = this.energy + other.energy,
      duration = this.duration + other.duration
    )
  }
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
