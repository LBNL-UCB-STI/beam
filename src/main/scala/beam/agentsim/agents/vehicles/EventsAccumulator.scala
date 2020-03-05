package beam.agentsim.agents.vehicles

import akka.actor.{Actor, ActorRef, Props}
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.events.{ChargingPlugInEvent, ChargingPlugOutEvent, RefuelSessionEvent}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.sim.config.BeamConfig

import scala.collection.mutable.ListBuffer

object EventsAccumulator {

  case class ProcessChargingEvents(event: org.matsim.api.core.v01.events.Event)

  case class EventsAccumulatorTrigger(tick: Int) extends Trigger

  def props(scheduler: ActorRef, beamConfig: BeamConfig): Props =
    Props(new EventsAccumulator(scheduler, beamConfig))

}

class EventsAccumulator(scheduler: ActorRef, beamConfig: BeamConfig) extends Actor {
  import EventsAccumulator._

  val timeout: Int = beamConfig.beam.agentsim.collectEventsIntervalInSeconds

  val chargingEventsBuffer: ListBuffer[org.matsim.api.core.v01.events.Event] =
    ListBuffer.empty[org.matsim.api.core.v01.events.Event]

  scheduler ! ScheduleTrigger(EventsAccumulatorTrigger(0), self)

  override def receive: Receive = {

    case t @ TriggerWithId(EventsAccumulatorTrigger(tick), _) =>
      scheduler ! CompletionNotice(t.triggerId)
      informExternalSystem(chargingEventsBuffer)
      clearStates()
      scheduler ! ScheduleTrigger(EventsAccumulatorTrigger(tick + timeout), self)

    case ProcessChargingEvents(e) =>
      chargingEventsBuffer += e

    case Finish =>
      informExternalSystem(chargingEventsBuffer)
      context.stop(self)
  }

  private def clearStates(): Unit = {
    chargingEventsBuffer.clear()
  }

  private def informExternalSystem(chargingEventsBuffer: ListBuffer[org.matsim.api.core.v01.events.Event]): Unit = {
    // TODO implement this stub later
  }
}
