package beam.agentsim.agents.vehicles

import akka.actor.{Actor, ActorRef, Props}
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.sim.BeamServices
import beam.utils.DateUtils
import helics.BeamFederate
import helics.BeamFederate.BeamFederateTrigger

import scala.collection.mutable.ListBuffer

object EventsAccumulator {
  case class ProcessChargingEvents(event: org.matsim.api.core.v01.events.Event)

  def props(scheduler: ActorRef, beamServices: BeamServices): Props =
    Props(new EventsAccumulator(scheduler, beamServices))
}

class EventsAccumulator(scheduler: ActorRef, beamServices: BeamServices) extends Actor {
  import EventsAccumulator._
  import beamServices._

  private val EOT: Int = DateUtils.getEndOfTime(beamConfig)
  private val beamFederate = BeamFederate.getInstance(beamServices)
  private val chargingEventsBuffer: ListBuffer[org.matsim.api.core.v01.events.Event] =
    ListBuffer.empty[org.matsim.api.core.v01.events.Event]

  // init
  scheduler ! ScheduleTrigger(BeamFederateTrigger(0), self)

  override def receive: Receive = {
    case t @ TriggerWithId(BeamFederateTrigger(tick), _) =>
      val nextTick = beamFederate.syncAndMoveToNextTimeStep(tick)
      chargingEventsBuffer.foreach(beamFederate.publish)
      clearStates()
      sender ! CompletionNotice(
        t.triggerId,
        if (tick < EOT)
          Vector(ScheduleTrigger(BeamFederateTrigger(nextTick), self))
        else
          Vector()
      )

    case ProcessChargingEvents(e) =>
      chargingEventsBuffer += e

    case Finish =>
      clearStates()
      beamFederate.close()
      context.stop(self)
  }

  private def clearStates(): Unit = {
    chargingEventsBuffer.clear()
  }
}
