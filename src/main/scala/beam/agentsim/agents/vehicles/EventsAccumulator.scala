package beam.agentsim.agents.vehicles

import akka.actor.{Actor, ActorRef, Props}
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.events.{ChargingPlugInEvent, ChargingPlugOutEvent, RefuelSessionEvent}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.sim.config.BeamConfig
import beam.utils.DateUtils
import helics.BeamFederate

import scala.collection.mutable.ListBuffer

object EventsAccumulator {

  case class ProcessChargingEvents(event: org.matsim.api.core.v01.events.Event)

  case class EventsAccumulatorTrigger(tick: Int) extends Trigger

  def props(scheduler: ActorRef, beamConfig: BeamConfig): Props =
    Props(new EventsAccumulator(scheduler, beamConfig))

}

class EventsAccumulator(scheduler: ActorRef, beamConfig: BeamConfig) extends Actor {
  import EventsAccumulator._

  private val EOT: Int = DateUtils.getEndOfTime(beamConfig)
  private val timeInterval: Int = beamConfig.beam.agentsim.collectEventsIntervalInSeconds
  private val federate1 = BeamFederate.getBeamFederate1(timeInterval)
  private val chargingEventsBuffer: ListBuffer[org.matsim.api.core.v01.events.Event] =
    ListBuffer.empty[org.matsim.api.core.v01.events.Event]

  scheduler ! ScheduleTrigger(EventsAccumulatorTrigger(timeInterval), self)

  override def receive: Receive = {

    case t @ TriggerWithId(EventsAccumulatorTrigger(tick), _) =>
      informExternalSystem(chargingEventsBuffer)
      clearStates()
      val triggers = if(tick < EOT) {
        Vector(ScheduleTrigger(EventsAccumulatorTrigger(tick + timeInterval), self))
      } else {
        Vector()
      }
      sender ! CompletionNotice(t.triggerId, triggers)

    case ProcessChargingEvents(e) =>
      chargingEventsBuffer += e

    case Finish =>
      informExternalSystem(chargingEventsBuffer)
      clearStates()
      federate1.close()
      context.stop(self)
  }

  private def clearStates(): Unit = {
    chargingEventsBuffer.clear()
  }

  private def informExternalSystem(chargingEventsBuffer: ListBuffer[org.matsim.api.core.v01.events.Event]): Unit = {
    chargingEventsBuffer.foreach {
      case e: ChargingPlugInEvent =>
        e.getEventType
        federate1.publishSOC(e.tick.toInt, e.getEventType, e.vehId.toString, e.stall.locationUTM, e.primaryFuelLevel)
      case e: ChargingPlugOutEvent =>
        federate1.publishSOC(e.tick.toInt, e.getEventType, e.vehId.toString, e.stall.locationUTM, e.primaryFuelLevel)
      case _: RefuelSessionEvent =>
      case _                     =>
    }
  }
}
