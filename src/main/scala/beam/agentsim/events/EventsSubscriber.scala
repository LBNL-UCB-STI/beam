package beam.agentsim.events

import akka.actor.Actor
import akka.event.Logging
import beam.agentsim.events.AgentsimEventsBus.MatsimEvent
import beam.agentsim.events.EventsSubscriber.{EndIteration, FinishProcessing, ProcessingFinished}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.events.IterationEndsEvent

object EventsSubscriber{
  val SUBSCRIBER_NAME:String = "MATSIMEventsSubscriber"

  trait SubscriberEvent
  case object StartProcessing extends SubscriberEvent
  case class StartIteration(iteration: Int) extends SubscriberEvent
  case class EndIteration(iteration: Int) extends SubscriberEvent
  case class FinishProcessing(iteration: Int) extends SubscriberEvent
  case class ProcessingFinished(iteration: Int) extends SubscriberEvent
  case object FileWritten extends SubscriberEvent

}

class EventsSubscriber (private val eventsManager: EventsManager) extends Actor {
  val log = Logging(context.system, this)


  type Event = MatsimEvent


  def receive: Receive = {

    case event: Event =>
      try {
        eventsManager.processEvent(event.wrappedEvent)
      }catch {
        case e:IllegalArgumentException =>
          log.error(s"$e")
      }

    case EndIteration(it) =>
      eventsManager.finishProcessing()
      //XXXX: Not sure if resetting the handlers here is a good idea
//      eventsManager.resetHandlers(it)
      cleanupWriter()
      sender() ! ProcessingFinished(it)

    case _ => log.info("received unknown message")
  }

  /**
    * Used to process the events file into output useful for visualization.
    */
  // XXXX: comment out until a) we figure out fate of viz format, b) realtime considerations, or c) necessary [saf 10/10/2017]
  private def cleanupWriter(): Unit = {
    //    JsonUtils.processEventsFileVizData(beamServices.matsimServices.getControlerIO.getIterationFilename(currentIter, s"events.xml${gzExtension}"),
    //      beamServices.matsimServices.getControlerIO.getOutputFilename("trips.json"))
  }
}


