package beam.agentsim.agents.vehicles

import akka.actor.{Actor, ActorRef, Props}
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.events.{ChargingPlugInEvent, ChargingPlugOutEvent, RefuelSessionEvent}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.sim.config.BeamConfig

import scala.collection.mutable.ListBuffer

object ChargingEventsAccumulator {

  case class ChargingEventsAccumulatorTrigger(tick: Int) extends Trigger

  def props(scheduler: ActorRef, beamConfig: BeamConfig): Props =
    Props(new ChargingEventsAccumulator(scheduler, beamConfig))

}

class ChargingEventsAccumulator(scheduler: ActorRef, beamConfig: BeamConfig) extends Actor {
  import ChargingEventsAccumulator._

  val timeout: Int = beamConfig.beam.agentsim.agents.vehicles.collectChargingEventsIntervalInSeconds

  val chargingEventsBuffer: ListBuffer[org.matsim.api.core.v01.events.Event] =
    ListBuffer.empty[org.matsim.api.core.v01.events.Event]

  scheduler ! ScheduleTrigger(ChargingEventsAccumulatorTrigger(timeout), self)

  override def receive: Receive = {

    case t @ TriggerWithId(ChargingEventsAccumulatorTrigger(_), _) =>
      informExternalSystem(chargingEventsBuffer)
      chargingEventsBuffer.clear()
      scheduler ! CompletionNotice(t.triggerId)

    case e: RefuelSessionEvent =>
      chargingEventsBuffer += e

    case e: ChargingPlugInEvent =>
      chargingEventsBuffer += e

    case e: ChargingPlugOutEvent =>
      chargingEventsBuffer += e

    case Finish =>
      informExternalSystem(chargingEventsBuffer)
      context.stop(self)

  }

  private def informExternalSystem(chargingEventsBuffer: ListBuffer[org.matsim.api.core.v01.events.Event]): Unit = {
    //TODO implement this stub later
  }
}
