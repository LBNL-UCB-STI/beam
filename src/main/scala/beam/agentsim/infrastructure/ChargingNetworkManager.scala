package beam.agentsim.infrastructure

import akka.actor.{Actor, ActorLogging, Terminated}
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.infrastructure.ChargingNetworkManager.PlanningTimeOutTrigger
import beam.agentsim.infrastructure.power.{PowerController, SitePowerManager}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import beam.utils.DateUtils
import org.matsim.api.core.v01.Id

import scala.collection.concurrent.TrieMap

class ChargingNetworkManager(
  beamServices: BeamServices,
  beamConfig: BeamConfig,
  privateVehicles: TrieMap[Id[BeamVehicle], BeamVehicle]
) extends Actor
    with ActorLogging {

  private val sitePowerManager = new SitePowerManager()
  private val powerController = new PowerController(beamServices, beamConfig)
  private val vehiclesCopies: TrieMap[Id[BeamVehicle], BeamVehicle] = TrieMap.empty
  private val endOfSimulationTime: Int = DateUtils.getEndOfTime(beamConfig)

  override def receive: Receive = {
    case TriggerWithId(PlanningTimeOutTrigger(tick), triggerId) =>
      log.info("PlanningTimeOutTrigger, tick: {}, triggerId: {}", tick, triggerId)

      val requiredPower = sitePowerManager.getPowerOverPlanningHorizon(privateVehicles)

      powerController.publishPowerOverPlanningHorizon(requiredPower)
      val (bounds, nextTick) = powerController.calculatePower(requiredPower, tick)
      val requiredEnergyPerVehicle = sitePowerManager.replanHorizonAndGetChargingPlanPerVehicle(bounds, privateVehicles)

      log.info("Required energy per vehicle before charging: {}", requiredEnergyPerVehicle.mkString(","))

      requiredEnergyPerVehicle.foreach {
        case (id, energy) if energy > 0 =>
          val vehicleCopy = vehiclesCopies.getOrElse(id, makeBeamVehicleCopy(privateVehicles(id)))
          vehicleCopy.addFuel(energy)
          if (vehicleCopy.beamVehicleType.primaryFuelCapacityInJoule == vehicleCopy.primaryFuelLevelInJoules) {
            // vehicle is fully charged
            vehiclesCopies.remove(vehicleCopy.id)
          }
        case _ => ()
      }

      log.info("Copies of vehicles (dummy vehicles) after charging: {}", vehiclesCopies.mkString(","))

      sender ! CompletionNotice(
        triggerId,
        if (tick < endOfSimulationTime)
          Vector(ScheduleTrigger(PlanningTimeOutTrigger(tick + nextTick), self))
        else
          Vector()
      )

    case Finish =>
      log.info("Finish sent by {}", sender)
      powerController.close()

    case Terminated(_) =>
      log.info("Terminated sent by {}", sender)
  }

  private def makeBeamVehicleCopy(vehicle: BeamVehicle): BeamVehicle = {
    val vehicleCopy = new BeamVehicle(
      vehicle.id,
      vehicle.powerTrain,
      vehicle.beamVehicleType,
      vehicle.randomSeed
    )
    vehicleCopy.setManager(vehicle.getManager)
    vehicleCopy.setMustBeDrivenHome(vehicle.isMustBeDrivenHome)
    vehicleCopy.setReservedParkingStall(vehicle.reservedStall)
    vehicleCopy
  }
}

object ChargingNetworkManager {
  case class PlanningTimeOutTrigger(tick: Int) extends Trigger
}
