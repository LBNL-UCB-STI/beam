package beam.utils.analysis

import beam.utils.Statistics
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.{Event, PersonArrivalEvent, PersonDepartureEvent}
import org.matsim.core.events.handler.BasicEventHandler

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

class TravelTimeHandler(isCACCVehicle: scala.collection.Map[String, Boolean])
    extends BasicEventHandler
    with StrictLogging {
  case class ArrivalDepartureEvent(personId: String, time: Int, `type`: String)

  type PersonToDepartureTimeMap = mutable.HashMap[Id[Person], Double]

  private val modeToEvents = new mutable.HashMap[String, ArrayBuffer[ArrivalDepartureEvent]]
  private val modeToPersonMap = new mutable.HashMap[String, PersonToDepartureTimeMap]

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
        val events = modeToEvents.getOrElse(pae.getLegMode, {
          val buf = new ArrayBuffer[ArrivalDepartureEvent]()
          modeToEvents.put(pae.getLegMode, buf)
          buf
        })
        events += ArrivalDepartureEvent(pae.getPersonId.toString, pae.getTime.toInt, "arrival")

      case pde: PersonDepartureEvent =>
        val events = modeToEvents.getOrElse(pde.getLegMode, {
          val buf = new ArrayBuffer[ArrivalDepartureEvent]()
          modeToEvents.put(pde.getLegMode, buf)
          buf
        })
        events += ArrivalDepartureEvent(pde.getPersonId.toString, pde.getTime.toInt, "departure")
      case _ =>
    }
  }

  private def getOrInitMap(mode: String): PersonToDepartureTimeMap = {
    val map = modeToPersonMap.getOrElse(mode, {
      val r = new PersonToDepartureTimeMap
      modeToPersonMap.put(mode, r)
      r
    })
    map
  }

  def compute: Map[String, Statistics] = {
    modeToEvents.map {
      case (mode, events) =>
        mode -> compute(events)
    }.toMap
  }

  def compute(events: IndexedSeq[ArrivalDepartureEvent]): Statistics = {
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
