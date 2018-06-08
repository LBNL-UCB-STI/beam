package beam.agentsim.agents.rideHail

import beam.agentsim.events.PathTraversalEvent
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.events.{Event, PersonEntersVehicleEvent}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.events.handler.BasicEventHandler

import scala.collection.mutable

class RideHailDebugEventHandler(eventsManager: EventsManager) extends BasicEventHandler with LazyLogging {


  eventsManager.addHandler(this)

  private val vehicleEvents = mutable.Map[String, Event]()

  override def handleEvent(event: Event): Unit = {
    vehiclesWithNoPassengers(event)
  }


  private def vehiclesWithNoPassengers(event: Event) = {
    // if peson enters ride hail vehicle then number of passengers >0 in ride hail vehicle

    event.getEventType match {
      case PersonEntersVehicleEvent.EVENT_TYPE =>

        val vehicle = event.getAttributes.get(PersonEntersVehicleEvent.ATTRIBUTE_VEHICLE)
        if (vehicle.contains("rideHail"))
          vehicleEvents.put(vehicle, event)
      case PathTraversalEvent.EVENT_TYPE =>
        val mode = event.getAttributes.get(PathTraversalEvent.ATTRIBUTE_MODE)
        val vehicle = event.getAttributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID)
        val numPassengers = event.getAttributes.get(PathTraversalEvent.ATTRIBUTE_NUM_PASS).toInt
        if (vehicle.contains("rideHail")) {
          if (numPassengers == 0) {
            vehicleEvents.remove(vehicle)
            logger.debug(s"RideHail: vehicle with no passenger where it already encountered PersonEntersVehicle $event")
          }

        }
      case _ =>
    }
  }

  override def reset(iteration: Int): Unit = {


  }

}
