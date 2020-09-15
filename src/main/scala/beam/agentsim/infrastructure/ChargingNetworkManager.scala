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
    log.info("ChargingNetworkManager is connected to grid: {}", powerController.isConnectedToGrid)
  }

  override def receive: Receive = {
    case ChargingPlugRequest(vehicle, drivingAgent) =>
      log.info(
        "ChargingPlugRequest for vehicle {} by agent {} on stall {}",
        vehicle,
        drivingAgent.path.name,
        vehicle.stall
      )
      vehiclesToCharge.put(vehicle.id, ChargingVehicle(vehicle, drivingAgent, 0))

    case ChargingUnplugRequest(vehicle, tick) =>
      log.info("ChargingUnplugRequest for vehicle {} at {}", vehicle, tick)

      vehiclesToCharge
        .remove(vehicle.id)
        .map { cv =>
          log.debug(
            "Vehicle {} is removed from ChargingManager. Scheduling EndRefuelSessionTrigger at {} with {} J delivered",
            vehicle,
            tick,
            cv.totalEnergy
          )
          scheduler ! ScheduleTrigger(
            EndRefuelSessionTrigger(tick, vehicle.getChargerConnectedTick(), cv.totalEnergy, vehicle),
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
          val cv @ ChargingVehicle(vehicle, agent, totalProvidedEnergy) = vehiclesToCharge(vehicleId)
          log.debug("Charging vehicle {}. Required energy = {}", vehicle, requiredEnergy)

          val (chargingDuration, providedEnergy) =
            vehicle.refuelingSessionDurationAndEnergyInJoules(Some(maxChargeDuration))

          vehicle.addFuel(providedEnergy)
          val newTotalProvidedEnergy = totalProvidedEnergy + providedEnergy

          endRefuelSessionTriggerMaybe(
            vehicle,
            tick,
            chargingDuration,
            providedEnergy,
            maxChargeDuration,
            newTotalProvidedEnergy
          ).map { endRefuelSession =>
              vehiclesToCharge.remove(vehicleId)
              ScheduleTrigger(endRefuelSession, agent)
            }
            .orElse {
              vehiclesToCharge.update(vehicleId, cv.copy(totalEnergy = newTotalProvidedEnergy))
              None
            }

        case (id, energy) if energy < 0 =>
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
                EndRefuelSessionTrigger(tick, cv.vehicle.getChargerConnectedTick(), cv.totalEnergy, cv.vehicle),
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
    chargingDuration: Long,
    providedEnergy: Double,
    currentChargeDuration: Long,
    totalProvidedEnergy: Double
  ): Option[EndRefuelSessionTrigger] = {
    if (vehicle.primaryFuelLevelInJoules >= vehicle.beamVehicleType.primaryFuelCapacityInJoule) {
      log.debug(
        "Vehicle {} is fully charged. Scheduling EndRefuelSessionTrigger at {} with {} J delivered",
        vehicle.id,
        tick + chargingDuration.toInt,
        totalProvidedEnergy
      )
      Some(
        EndRefuelSessionTrigger(
          tick + chargingDuration.toInt,
          vehicle.getChargerConnectedTick(),
          totalProvidedEnergy,
          vehicle
        )
      )
    } else if (chargingDuration < currentChargeDuration) {
      log.debug(
        "Vehicle {} is charged by a short time: {}. Scheduling EndRefuelSessionTrigger at {} with {} J delivered",
        vehicle.id,
        chargingDuration,
        tick + chargingDuration.toInt,
        totalProvidedEnergy
      )
      Some(
        EndRefuelSessionTrigger(
          tick + chargingDuration.toInt,
          vehicle.getChargerConnectedTick(),
          totalProvidedEnergy,
          vehicle
        )
      )
    } else {
      log.debug(
        "Ending refuel cycle for vehicle {}. Provided {} J. during {}",
        vehicle.id,
        providedEnergy,
        chargingDuration
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
  final case class ChargingVehicle(vehicle: BeamVehicle, agent: ActorRef, totalEnergy: Double)
}
