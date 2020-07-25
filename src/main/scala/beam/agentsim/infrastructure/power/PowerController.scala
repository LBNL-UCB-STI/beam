package beam.agentsim.infrastructure.power

import akka.actor.{Actor, ActorLogging, ActorRef}
import beam.agentsim.infrastructure.power.SitePowerManager.PowerOverPlanningHorizon
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.cosim.helics.BeamFederate
import beam.sim.BeamServices

import scala.util.Try

class PowerController(beamServices: BeamServices) extends Actor with ActorLogging {
  import PowerController._

  private lazy val beamFederate: BeamFederate = BeamFederate.getInstance(beamServices)
  private lazy val isConnectedToHelics: Boolean = Try(beamFederate).isSuccess

  override def receive: Receive = {
    case msg @ ConnectedToHelicsRequest() =>
      import msg.ctx
      sender ! ConnectedToHelicsResponse(isConnectedToHelics)
    case msg @ CalculatePowerRequest(_, tick) =>
      import msg.ctx
      // TODO to be implemented
      val nextTick = beamFederate.syncAndMoveToNextTimeStep(tick)
      sender ! CalculatePowerResponse(PhysicalBounds.default(10.0), nextTick)

  }
}

object PowerController {
  // actor's protocol
  case class MsgCtx(scheduler: ActorRef, triggerWithId: TriggerWithId)

  case class ConnectedToHelicsRequest()(implicit val ctx: MsgCtx)
  case class ConnectedToHelicsResponse(isConnected: Boolean)(implicit val ctx: MsgCtx)

  case class CalculatePowerRequest(plannedPower: PowerOverPlanningHorizon, tick: Int)(implicit val ctx: MsgCtx)
  case class CalculatePowerResponse(bounds: PhysicalBounds, nextTick: Int)(implicit val ctx: MsgCtx)

  // actor's internal model
  case class PhysicalBounds(minPower: Double, maxPower: Double, price: Double)

  object PhysicalBounds {
    def default(requiredPower: Double): PhysicalBounds = PhysicalBounds(requiredPower, requiredPower, 0)
  }
}
