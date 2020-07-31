package beam.agentsim.agents.vehicles

import akka.actor.{Actor, Props}
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.cosim.helics.BeamFederate
import beam.cosim.helics.BeamFederate.BeamFederateTrigger
import beam.sim.BeamServices
import beam.utils.DateUtils

import scala.collection.mutable.ListBuffer

object EventsAccumulator {
  case class ProcessChargingEvents(event: org.matsim.api.core.v01.events.Event)

  def props(beamServices: BeamServices): Props =
    Props(new EventsAccumulator(beamServices))
}

class EventsAccumulator(beamServices: BeamServices) extends Actor {
  import EventsAccumulator._
  import beamServices._

  private val endOfSimulationTime: Int = DateUtils.getEndOfTime(beamConfig)
  private val beamFederate = BeamFederate.getInstance(beamServices)
  private val chargingEventsBuffer: ListBuffer[org.matsim.api.core.v01.events.Event] =
    ListBuffer.empty[org.matsim.api.core.v01.events.Event]

  override def receive: Receive = {
    case t @ TriggerWithId(BeamFederateTrigger(tick), _) =>
      val nextTick = beamFederate.syncAndMoveToNextTimeStep(tick)
      chargingEventsBuffer.foreach(beamFederate.publish(_, tick))
      clearStates()
      sender ! CompletionNotice(
        t.triggerId,
        if (tick < endOfSimulationTime)
          Vector(ScheduleTrigger(BeamFederateTrigger(nextTick), self))
        else
          Vector()
      )

    case ProcessChargingEvents(e) =>
      chargingEventsBuffer += e

    case Finish =>
      clearStates()
      beamFederate.close()
  }

  private def clearStates(): Unit = {
    chargingEventsBuffer.clear()
  }
}
