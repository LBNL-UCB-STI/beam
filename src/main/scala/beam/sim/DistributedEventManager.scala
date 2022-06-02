package beam.sim

import akka.actor.{Actor, ActorRef, Props}
import beam.agentsim.events.PathTraversalEvent
import org.matsim.api.core.v01.events.Event
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.events.handler.EventHandler

/**
  * @author Dmitry Openkov
  */
class DistributedEventManager(eventManager: EventsManager) extends Actor {

  override def receive: Receive = { case e: Event =>
    eventManager.processEvent(e)
  }
}

object DistributedEventManager {
  def props(eventManager: EventsManager): Props = Props(new DistributedEventManager(eventManager))
}

class DuplicatingEventManager(distributedEM: ActorRef, localEM: EventsManager) extends EventsManager {

  override def processEvent(event: Event): Unit = event match {
    case pte: PathTraversalEvent =>
      distributedEM ! pte
      localEM.processEvent(pte)
    case ev =>
      localEM.processEvent(ev)
  }

  override def addHandler(handler: EventHandler): Unit = ???

  override def removeHandler(handler: EventHandler): Unit = ???

  override def resetHandlers(iteration: Int): Unit = ???

  override def initProcessing(): Unit = ???

  override def afterSimStep(time: Double): Unit = ???

  override def finishProcessing(): Unit = ???
}
