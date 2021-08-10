package beam.agentsim.events.eventbuilder

import akka.actor.{Actor, ActorLogging, Props}
import beam.agentsim.events.eventbuilder.EventBuilderActor.{EventBuilderActorCompleted, FlushEvents}

/**
  * When information needed to create events is spread over multiple actors and classes, partial event information is
  * sent to this actor in order to create the event when all information has been collected.
  *
  * @param complexEventBuilders a [[List]] of [[ComplexEventBuilder]]s that build events from partial information messages.
  */
class EventBuilderActor(
  val complexEventBuilders: List[ComplexEventBuilder]
) extends Actor
    with ActorLogging {

  var hasFlushed = false

  /**
    * All messages other than the 'FlushEvents' message is forwarded to the complex event builders for event creation.
    * Awaiting FlushEvents and sending back EventBuilderActorCompleted message to sender allows iteration to complete
    * after all queued messages at the event builder actor have been processed. Checking hasFlushed handles case
    * if multiple FlushEvents are received.
    */
  override def receive: Receive = {
    case FlushEvents =>
      if (!hasFlushed) {
        complexEventBuilders.foreach(x => x.logEndOfIterationStatus())
        hasFlushed = true
        sender() ! EventBuilderActorCompleted
      }
    case message =>
      complexEventBuilders.foreach(x => x.handleMessage(message))
  }
}

object EventBuilderActor {

  def props(
    eventBuilders: List[ComplexEventBuilder]
  ): Props = {
    Props(classOf[EventBuilderActor], eventBuilders)
  }
  case object FlushEvents
  case object EventBuilderActorCompleted
}
