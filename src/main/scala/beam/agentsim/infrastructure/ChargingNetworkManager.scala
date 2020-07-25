package beam.agentsim.infrastructure

import akka.actor.{Actor, ActorLogging, ActorRef}
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.EndRefuelSessionTrigger
import beam.agentsim.infrastructure.ChargingNetworkManager.PlanningTimeOutTrigger
import beam.agentsim.infrastructure.power.{PowerController, SitePowerManager}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.sim.config.BeamConfig
import beam.utils.DateUtils

class ChargingNetworkManager(
  beamConfig: BeamConfig,
  sitePowerManager: SitePowerManager,
  powerController: ActorRef
) extends Actor
    with ActorLogging {

  import PowerController._

  private val endOfSimulationTime: Int = DateUtils.getEndOfTime(beamConfig)

  override def receive: Receive = {
    case t @ TriggerWithId(PlanningTimeOutTrigger(tick), triggerId) =>
      log.info(s"PlanningTimeOutTrigger, tick: $tick, triggerId: $triggerId")
      powerController ! ConnectedToHelicsRequest()(MsgCtx(sender, t))

    case msg @ ConnectedToHelicsResponse(true) =>
      import msg.ctx
      val plannedPower = sitePowerManager.getPowerOverPlanningHorizon
      val tick = ctx.triggerWithId.trigger.tick
      powerController ! CalculatePowerRequest(plannedPower, tick)

    case msg @ ConnectedToHelicsResponse(false) =>
      import msg.ctx
      val nextTick = beamConfig.beam.cosim.helics.timeStep * 4 // TODO what is the multiplier ???
      // in case of not connected to helics we simulate calculation are finished with default PhysicalBounds
      self ! CalculatePowerResponse(PhysicalBounds.default(0.0), nextTick)

    case msg @ CalculatePowerResponse(bounds, nextTick) =>
      import msg.ctx

      sitePowerManager.replanHorizon(bounds)
      val vehiclesWithRequiredEnergy = sitePowerManager.getChargingPlanPerVehicle

      val triggerId = ctx.triggerWithId.triggerId
      val tick = ctx.triggerWithId.trigger.tick

      val endRefuelSessionTriggers = vehiclesWithRequiredEnergy.map {
        case (vehicle, requiredEnergy) =>
          // TODO where is [for all ChargingPlugs]
          // TODO where is vehicle endTime?
          vehicle.addFuel(requiredEnergy)
          // TODO Where is StartRefuelSessionTrigger ???
          // TODO where to get sessionDuration ?
          val sessionDuration = 100
          ScheduleTrigger(EndRefuelSessionTrigger(tick + sessionDuration.toInt, tick, requiredEnergy, vehicle), self)
      }.toVector

      ctx.scheduler ! CompletionNotice(
        triggerId,
        if (tick < endOfSimulationTime)
          endRefuelSessionTriggers :+ ScheduleTrigger(PlanningTimeOutTrigger(tick + nextTick), self)
        else
          Vector()
      )

    case Finish =>
      log.info(s"Finish") // Not sure if this called
  }
}

object ChargingNetworkManager {
  case class PlanningTimeOutTrigger(tick: Int) extends Trigger
}
