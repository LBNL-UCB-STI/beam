package beam.physsim.jdeqsim

import beam.utils.Statistics
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.{Event, PersonArrivalEvent, PersonDepartureEvent}
import org.matsim.api.core.v01.population.Person
import org.matsim.core.events.handler.BasicEventHandler

import scala.collection.mutable.ArrayBuffer

class CarTravelTimeHandler(isCACCVehicle: scala.collection.Map[String, Boolean])
    extends BasicEventHandler
    with StrictLogging {
  case class ArrivalDepartureEvent(personId: String, time: Int, `type`: String)

  private val events = new ArrayBuffer[ArrivalDepartureEvent]

  def shouldTakeThisEvent(personId: Id[Person], legMode: String): Boolean = {
    val pid = personId.toString.toLowerCase
    val isCacc = isCACCVehicle.getOrElse(pid, false)
    // No buses && no ridehailes and no cacc vehicles
    val isInvalid = pid.contains(":") || pid.contains("ridehailvehicle") || isCacc
    !isInvalid
  }

  override def handleEvent(event: Event): Unit = {
    event match {
      case pae: PersonArrivalEvent =>
        if (shouldTakeThisEvent(pae.getPersonId, pae.getLegMode)) {
          events += ArrivalDepartureEvent(pae.getPersonId.toString, pae.getTime.toInt, "arrival")
        }
      case pde: PersonDepartureEvent =>
        if (shouldTakeThisEvent(pde.getPersonId, pde.getLegMode)) {
          events += ArrivalDepartureEvent(pde.getPersonId.toString, pde.getTime.toInt, "departure")
        }
      case _ =>
    }
  }

  def compute: Statistics = {
    val groupedByPerson = events.groupBy(x => x.personId)
    val allTravelTimes = groupedByPerson.flatMap {
      case (personId, xs) =>
        val sorted = xs.sortBy(z => z.time)
        val sliding = sorted.sliding(2, 2)
        val travelTimes = sliding
          .map { curr =>
            if (curr.size != 2) {
              0
            } else {
              val travelTime = (curr(1).time - curr(0).time)
              travelTime
            }
          }
          .filter(x => x != 0)
        travelTimes
    }
    Statistics(allTravelTimes.map(t => t.toDouble / 60).toArray)
  }
}
