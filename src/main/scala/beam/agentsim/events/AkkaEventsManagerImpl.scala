package beam.agentsim.events

import java.util.concurrent.TimeUnit
import javax.inject.Inject

import akka.actor.{Actor, ActorSystem, Props, Stash}
import akka.pattern.ask
import akka.util.Timeout
import beam.agentsim.events.AkkaEventsManagerImpl.{EventsSubscriber, FinishWaiter, Finished, FinishedAck}
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
    }
  }

  private class FinishWaiter extends Actor with Stash {
    import context._

    def receive: Receive = notFinished

    def notFinished: Receive = {
      case Finished() =>
        unstashAll()
        become(finished)
      case _ =>
        stash()
    }

    def finished: Receive = {
      case FinishedAck() =>
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

  override def processEvent(event: Event) = actorSystem.eventStream.publish(event)
  override def finishProcessing() = {
    val finishWaiter = actorSystem.actorOf(Props(classOf[FinishWaiter]) )
    actorSystem.eventStream.subscribe(finishWaiter, classOf[Finished])
    actorSystem.eventStream.publish(Finished())
    Await.result(finishWaiter ? FinishedAck(), timeout.duration)
    delegate.finishProcessing()
  }

  override def addHandler(handler: EventHandler) = delegate.addHandler(handler)
  override def removeHandler(handler: EventHandler) = delegate.removeHandler(handler)
  override def afterSimStep(time: Double) = delegate.afterSimStep(time)
  override def resetHandlers(iteration: Int) = delegate.resetHandlers(iteration)
  override def initProcessing() = delegate.initProcessing()

}
