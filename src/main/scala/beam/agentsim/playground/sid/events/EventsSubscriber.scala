package beam.agentsim.playground.sid.events

import akka.actor.Actor
import akka.event.Logging
import beam.agentsim.playground.sid.events.AgentsimEventsBus.MatsimEvent
import beam.agentsim.playground.sid.events.EventsSubscriber.{EndIteration, FinishProcessing, StartIteration, StartProcessing}
import beam.agentsim.sim.AgentsimServices
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.events.algorithms.EventWriterXML

object EventsSubscriber{
  case object StartProcessing
  case class StartIteration(iteration: Int)
  case class EndIteration(iteration: Int)
  case object FinishProcessing
}

class EventsSubscriber (private val eventsManager: EventsManager,agentsimServices: AgentsimServices) extends Actor {
  val log = Logging(context.system, this)
  var writer: EventWriterXML = _
  type Event = MatsimEvent


  def receive: Receive = {

    case StartProcessing =>
      eventsManager.initProcessing()

    case event: Event =>
      eventsManager.processEvent(event.wrappedEvent)

    case FinishProcessing =>
      eventsManager.finishProcessing()

    case StartIteration(iter)=>
      writer = new EventWriterXML(agentsimServices.matsimServices.getControlerIO.getIterationFilename(iter,"events.xml.gz"))
      eventsManager.addHandler(writer)

    case EndIteration(iter) =>
      eventsManager.resetHandlers(iter)
      writer.closeFile()
      eventsManager.removeHandler(writer)


    case _ => log.info("received unknown message")
  }
}
