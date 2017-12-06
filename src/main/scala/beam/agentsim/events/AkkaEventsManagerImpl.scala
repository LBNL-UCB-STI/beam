package beam.agentsim.events

import java.util.concurrent.TimeUnit
import javax.inject.Inject

import akka.actor.{Actor, ActorSystem, Props, Stash}
import akka.pattern.ask
import akka.util.Timeout
import beam.agentsim.events.AkkaEventsManagerImpl.{EventsSubscriber, Finished, FinishedAck}
import org.matsim.api.core.v01.events.Event
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.events.handler.EventHandler

import scala.concurrent.Await

object AkkaEventsManagerImpl {

  private case class Finished()
  private case class FinishedAck()

  private class EventsSubscriber(val eventsManager: EventsManager) extends Actor {
    def receive: Receive = {
      case event: Event =>
        eventsManager.processEvent(event)
      case Finished() =>
        sender ! FinishedAck()
        context.stop(self)
    }
  }

}

class AkkaEventsManagerImpl @Inject()(val actorSystem: ActorSystem) extends EventsManager {

  private implicit val timeout = Timeout(50000, TimeUnit.SECONDS)

  private val delegate = new EventsManagerImpl()
  private val eventSubscriber = actorSystem.actorOf(Props(classOf[EventsSubscriber], delegate))
  actorSystem.eventStream.subscribe(eventSubscriber, classOf[Event])
  actorSystem.eventStream.subscribe(eventSubscriber, classOf[Finished])

  override def processEvent(event: Event) = actorSystem.eventStream.publish(event)
  override def finishProcessing() = {
    Await.result(eventSubscriber ? Finished(), timeout.duration)
    delegate.finishProcessing()
  }

  override def addHandler(handler: EventHandler) = delegate.addHandler(handler)
  override def removeHandler(handler: EventHandler) = delegate.removeHandler(handler)
  override def afterSimStep(time: Double) = delegate.afterSimStep(time)
  override def resetHandlers(iteration: Int) = delegate.resetHandlers(iteration)
  override def initProcessing() = delegate.initProcessing()

}
