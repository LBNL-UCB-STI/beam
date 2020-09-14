package beam.agentsim.infrastructure

import akka.actor.{Actor, ActorLogging, ActorRef}
import beam.agentsim.agents.modalbehaviors.DrivesVehicle._
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.infrastructure.ChargingNetworkManager.PlanningTimeOutTrigger
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
      vehiclesToCharge.put(vehicle.id, ChargingVehicle(vehicle, drivingAgent, 0, 0))

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

      val scheduleTriggers = requiredEnergyPerVehicle.flatMap {
        case (vehicleId, requiredEnergy) if requiredEnergy > 0 =>
          val cv @ ChargingVehicle(vehicle, agent, totalDuration, totalEnergy) = vehiclesToCharge(vehicleId)
          log.debug(
            "Charging vehicle {} (primaryFuelLevel = {}). Required energy = {}",
            vehicle,
            vehicle.primaryFuelLevelInJoules,
            requiredEnergy
          )

          val (chargingDuration, providedEnergy) = vehicle.refuelingSessionDurationAndEnergyInJoules()
          // TODO calc chargingDuration

          vehicle.addFuel(providedEnergy)
          val updCv = cv.copy(
            totalDuration = totalDuration + chargingDuration,
            totalEnergy = totalEnergy + providedEnergy
          )

          if (vehicle.primaryFuelLevelInJoules >= vehicle.beamVehicleType.primaryFuelCapacityInJoule) {
            log.debug(
              "Vehicle {} is fully charged. Scheduling EndRefuelSessionTrigger at {} with {} J delivered",
              vehicleId,
              tick + 1,
              updCv.totalEnergy
            )

            // vehicle is fully charged. Schedule trigger and remove it from vehicles
            vehiclesToCharge.remove(vehicleId)
            Option(
              ScheduleTrigger(
                EndRefuelSessionTrigger(tick + 1, vehicle.getChargerConnectedTick(), updCv.totalEnergy, vehicle),
                agent
              )
            )
          } else {
            log.debug(
              "Ending refuel cycle for vehicle {}. Required {} J. Provided {} J. during {}",
              vehicleId,
              requiredEnergy,
              providedEnergy,
              chargingDuration
            )

            vehiclesToCharge.update(vehicleId, updCv)
            None
          }

        case (id, energy) if energy < 0 =>
          log.warning(
            "Vehicle {}  (primaryFuelLevel = {}) requires negative energy {} - how could it be?",
            vehicles(id),
            vehicles(id).primaryFuelLevelInJoules,
            energy
          )
          None
        case (id, 0) =>
          log.debug(
            "Vehicle {} is fully charged (primaryFuelLevel = {})",
            vehicles(id),
            vehicles(id).primaryFuelLevelInJoules
          )
          None
      }.toVector

      if (vehicles.nonEmpty)
        log.debug("Vehicles after charging: {}", vehicles.mkString(","))

      sender ! CompletionNotice(
        triggerId,
        if (tick < endOfSimulationTime)
          scheduleTriggers :+ ScheduleTrigger(PlanningTimeOutTrigger(nextTick), self)
        else
          scheduleTriggers
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
  final case class ChargingVehicle(vehicle: BeamVehicle, agent: ActorRef, totalDuration: Long, totalEnergy: Double)
}
