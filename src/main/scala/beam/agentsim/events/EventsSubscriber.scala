package beam.agentsim.events

import akka.actor.Actor
import akka.event.Logging
import beam.agentsim.events.AgentsimEventsBus.MatsimEvent
import beam.agentsim.events.EventsSubscriber._
import beam.agentsim.sim.AgentsimServices
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.events.algorithms.EventWriterXML

object EventsSubscriber{
  sealed trait SubscriberEvent
  case object StartProcessing extends SubscriberEvent
  case class StartIteration(iteration: Int) extends SubscriberEvent
  case class EndIteration(iteration: Int) extends SubscriberEvent
  case object FinishProcessing extends SubscriberEvent
  case object ProcessingFinished extends SubscriberEvent
  case object FileWritten extends SubscriberEvent
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
      sender ! ProcessingFinished

    case StartIteration(iter)=>
      writer = new EventWriterXML(agentsimServices.matsimServices.getControlerIO.getIterationFilename(iter,"events.xml.gz"))
      eventsManager.addHandler(writer)

    case EndIteration(iter) =>
      eventsManager.resetHandlers(iter)
      writer.closeFile()
      eventsManager.removeHandler(writer)
      sender ! FileWritten


    case _ => log.info("received unknown message")
  }
}


