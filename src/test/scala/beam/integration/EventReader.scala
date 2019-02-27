package beam.integration

import java.io.File

import beam.agentsim.events._
import org.matsim.api.core.v01.events.{Event, GenericEvent}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.config.Config
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.events.{EventsUtils, MatsimEventsReader}

import scala.collection.mutable.ArrayBuffer

object EventReader {

  def fromFile(filePath: String): IndexedSeq[Event] = {
    val eventsManager = EventsUtils.createEventsManager()
    val events = new ArrayBuffer[Event]
    eventsManager.addHandler(new BasicEventHandler {
      def handleEvent(event: Event): Unit = {
        events += event
      }
    })
    fromFile(filePath, eventsManager)
    events
  }

  def fromFile(filePath: String, eventsManager: EventsManager): Unit = {
    val tempEventManager = EventsUtils.createEventsManager()
    tempEventManager.addHandler(new BasicEventHandler {
      def handleEvent(event: Event): Unit = {
        val fixedEvent = event match {
          case genericEvent: GenericEvent =>
            fixEvent(genericEvent)
          case _ => event
        }
        eventsManager.processEvent(fixedEvent)
      }
    })
    new MatsimEventsReader(tempEventManager).readFile(filePath)
  }

  def getEventsFilePath(matsimConfig: Config, extension: String, iteration: Int = 0): File = {
    new File(
      s"${matsimConfig.controler().getOutputDirectory}/ITERS/it.$iteration/$iteration.events.$extension"
    )
  }

  def fixEvent(event: GenericEvent): Event = {
    event.getEventType match {
      case PathTraversalEvent.EVENT_TYPE =>
        PathTraversalEvent(event)
      case LeavingParkingEvent.EVENT_TYPE =>
        LeavingParkingEvent(event)
      case ParkEvent.EVENT_TYPE =>
        ParkEvent(event)
      case ModeChoiceEvent.EVENT_TYPE =>
        ModeChoiceEvent.apply(event)
      case PersonCostEvent.EVENT_TYPE =>
        PersonCostEvent.apply(event)
      case ReserveRideHailEvent.EVENT_TYPE =>
        ReserveRideHailEvent.apply(event)
      case AgencyRevenueEvent.EVENT_TYPE =>
        AgencyRevenueEvent.apply(event)
      case ReplanningEvent.EVENT_TYPE =>
        ReplanningEvent.apply(event)
      case _ =>
        event
    }
  }

}
