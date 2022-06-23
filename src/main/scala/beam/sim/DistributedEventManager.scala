package beam.sim

import akka.actor.{Actor, ActorRef, Props}
import beam.agentsim.events.{FleetStoredElectricityEvent, PathTraversalEvent}
import org.matsim.api.core.v01.events.Event
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.events.handler.EventHandler

/**
  * @author Dmitry Openkov
  */
class DistributedEventManager(physsimEventManager: EventsManager, regularEventManager: EventsManager) extends Actor {

  override def receive: Receive = {
    case e: PathTraversalEvent => physsimEventManager.processEvent(e)
    case e: Event              => regularEventManager.processEvent(e)
  }
}

object DistributedEventManager {

  def props(eventManager: EventsManager, regularEventManager: EventsManager): Props =
    Props(new DistributedEventManager(eventManager, regularEventManager))
}

/**
  * We use this EM to pass all the PTE events to the master
  * @param distributedEM the actor that resides on the master that handles all the PTE event
  * @param localEM the local event manager to store/handle events locally.
  */
class DuplicatingEventManager(distributedEM: ActorRef, localEM: EventsManager) extends EventsManager {

  override def processEvent(event: Event): Unit = event match {
    case _: PathTraversalEvent | _: FleetStoredElectricityEvent =>
      distributedEM ! event
      localEM.processEvent(event)
    case _ =>
      localEM.processEvent(event)
  }

  override def addHandler(handler: EventHandler): Unit = ???

  override def removeHandler(handler: EventHandler): Unit = ???

  override def resetHandlers(iteration: Int): Unit = ???

  override def initProcessing(): Unit = ???

  override def afterSimStep(time: Double): Unit = ???

  override def finishProcessing(): Unit = ???
}
