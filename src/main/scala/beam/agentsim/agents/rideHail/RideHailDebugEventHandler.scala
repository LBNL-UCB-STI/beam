package beam.agentsim.agents.rideHail

import beam.agentsim.events.PathTraversalEvent
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.events._
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.events.handler.BasicEventHandler

import scala.collection.mutable

class RideHailDebugEventHandler(eventsManager: EventsManager) extends BasicEventHandler with LazyLogging {


  eventsManager.addHandler(this)

  //  private val rideHailEvents = mutable.Map[String, Event]()
  private var rideHailEvents = mutable.ArrayBuffer[Event]()

  override def handleEvent(event: Event): Unit = {

    collectRideHailEvents(event)

  }


  private def collectRideHailEvents(event: Event) = {
    // if peson enters ride hail vehicle then number of passengers >0 in ride hail vehicle

    event.getEventType match {
      case PersonEntersVehicleEvent.EVENT_TYPE | PersonLeavesVehicleEvent.EVENT_TYPE =>

        val person = event.getAttributes.get(PersonEntersVehicleEvent.ATTRIBUTE_PERSON)
        val vehicle = event.getAttributes.get(PersonEntersVehicleEvent.ATTRIBUTE_VEHICLE)
        if (vehicle.contains("rideHail") && !person.contains("rideHail"))
          rideHailEvents += event

      case PathTraversalEvent.EVENT_TYPE =>

        val vehicle = event.getAttributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID)
        if (vehicle.contains("rideHail"))
          rideHailEvents += event

      case _ =>

    }
  }

  override def reset(iteration: Int): Unit = {
    //TODO: fix execution for last iteration
    sortEvents()

    testZeroPassengerCount()

    rideHailEvents.clear()
  }

  private def testZeroPassengerCount(): Unit = {

    val vehicleEvents = mutable.Map[String, Event]()

    rideHailEvents.foreach(event =>

      event.getEventType match {

        case PersonEntersVehicleEvent.EVENT_TYPE =>

          val vehicle = event.getAttributes.get(PersonEntersVehicleEvent.ATTRIBUTE_VEHICLE)

          vehicleEvents.put(vehicle, event)

        case PathTraversalEvent.EVENT_TYPE if (vehicleEvents.size > 0) =>

          val vehicle = event.getAttributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID)
          val numPassengers = event.getAttributes.get(PathTraversalEvent.ATTRIBUTE_NUM_PASS).toInt
          val departure = event.getAttributes.get(PathTraversalEvent.ATTRIBUTE_DEPARTURE_TIME).toLong

          vehicleEvents.get(vehicle) match {

            case Some(enterEvent) =>

              val enteredVehicle = enterEvent.getAttributes.get(PersonEntersVehicleEvent.ATTRIBUTE_VEHICLE)

              if (enteredVehicle == vehicle && departure == enterEvent.getTime&& numPassengers == 0 )
                logger.debug(s"RideHail: vehicle with zero passenger - $event")

            case None =>
          }

        case PersonLeavesVehicleEvent.EVENT_TYPE =>

          val person = event.getAttributes.get(PersonLeavesVehicleEvent.ATTRIBUTE_PERSON)
          val vehicle = event.getAttributes.get(PersonLeavesVehicleEvent.ATTRIBUTE_VEHICLE)

          vehicleEvents.get(vehicle) match {

            case Some(enterEvent) =>

              val enteredPerson = enterEvent.getAttributes.get(PersonEntersVehicleEvent.ATTRIBUTE_PERSON)
              if (enteredPerson == person)
                vehicleEvents.remove(vehicle)
            case None =>
          }
        case _ =>

      })

    vehicleEvents.foreach(event => logger.debug(s"RideHail: Person enters vehicle but no leaves event encountered. $event"))
  }

  private def sortEvents(): Unit = {

    rideHailEvents = rideHailEvents.sortWith((e1, e2) => {
      if (e1.getEventType == e2.getEventType && e1.getEventType == PathTraversalEvent.EVENT_TYPE) {

        val e1Depart = e1.getAttributes.get(PathTraversalEvent.ATTRIBUTE_DEPARTURE_TIME).toLong
        val e2Depart = e2.getAttributes.get(PathTraversalEvent.ATTRIBUTE_DEPARTURE_TIME).toLong

        if(e1Depart != e2Depart) {

          return e1Depart < e1Depart

        } else {
          val e1Arrival = e1.getAttributes.get(PathTraversalEvent.ATTRIBUTE_ARRIVAL_TIME).toLong
          val e2Arrival = e2.getAttributes.get(PathTraversalEvent.ATTRIBUTE_ARRIVAL_TIME).toLong

          return e1Arrival < e2Arrival
        }
      }

      e1.getTime < e2.getTime
    })
  }
}
