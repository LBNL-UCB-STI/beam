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

  object EventTypes extends Enumeration {
    type EventTypes = Value
    val charging = Value
  }

  case class ProcessChargingEvents(event: org.matsim.api.core.v01.events.Event)

  case class ChargingEventsAccumulatorTrigger(tick: Int) extends Trigger

  def props(scheduler: ActorRef, beamConfig: BeamConfig): Props =
    Props(new EventsAccumulator(scheduler, beamConfig))

}

class EventsAccumulator(scheduler: ActorRef, beamConfig: BeamConfig) extends Actor {
  import EventsAccumulator._

  val timeout: Int = beamConfig.beam.agentsim.agents.vehicles.collectEventsIntervalInSeconds

  val enabledEvents: Array[String] =
    beamConfig.beam.agentsim.agents.vehicles.collectEventTypes.split(",").map(_.toLowerCase)

  val chargingEventsBuffer: ListBuffer[org.matsim.api.core.v01.events.Event] =
    ListBuffer.empty[org.matsim.api.core.v01.events.Event]

  scheduler ! ScheduleTrigger(ChargingEventsAccumulatorTrigger(timeout), self)

  override def receive: Receive = {

    case t @ TriggerWithId(ChargingEventsAccumulatorTrigger(_), _) =>
      informExternalSystem(chargingEventsBuffer)
      clearStates()
      scheduler ! CompletionNotice(t.triggerId)

    case ProcessChargingEvents(e) =>
      if (enabledEvents.contains(EventTypes.charging.toString.toLowerCase)) {
        chargingEventsBuffer += e
      }

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
