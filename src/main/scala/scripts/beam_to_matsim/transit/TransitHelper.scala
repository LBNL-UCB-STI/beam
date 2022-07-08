package beam.utils.beam_to_matsim.transit

import beam.utils.beam_to_matsim.events.{BeamPathTraversal, PathTraversalWithLinks, PathTraversalWithoutLinks}
import beam.utils.beam_to_matsim.transit.TransitEventsGroup.PassengerRange
import beam.utils.beam_to_matsim.via_event.{ViaActivity, ViaEvent, ViaPersonArrivalEvent, ViaPersonDepartureEvent}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.api.core.v01.network.Link

object TransitHelper extends LazyLogging {

  def round(d: Double): Double = BigDecimal.valueOf(d).setScale(3, BigDecimal.RoundingMode.HALF_UP).toDouble

  def departure(vehicleId: String, viaEvent: ViaEvent): ViaEvent =
    ViaPersonDepartureEvent(viaEvent.time, vehicleId, viaEvent.link)

  def arrival(vehicleId: String, viaEvent: ViaEvent): ViaEvent =
    ViaPersonArrivalEvent(viaEvent.time, vehicleId, viaEvent.link)

  def createViaEvents(
    events: Vector[BeamPathTraversal],
    vehicleId: String,
    linkStartAndEndMapMaybe: Option[Map[(Coord, Coord), Id[Link]]] = None
  ): Vector[ViaEvent] = {
    val uniqueVehicleIds = events.map(_.vehicleId).toSet
    assume(
      events.isEmpty || (uniqueVehicleIds.size == 1),
      s"Either events needs to be empty or vehicle ids should be same for all events. Found vehicle ids: $uniqueVehicleIds"
    )

    if (events.isEmpty) Vector()
    else {
      val viaEvents: Vector[ViaEvent] = events.flatMap {
        case p: PathTraversalWithLinks => p.toViaEvents(vehicleId, None)
        case p: PathTraversalWithoutLinks =>
          assume(
            linkStartAndEndMapMaybe.nonEmpty,
            "User must provide link information for PathTraversal events without `links`."
          )
          val linkLocationStore = linkStartAndEndMapMaybe.get
          linkLocationStore
            .get((p.startCoord, p.endCoord))
            .map { linkId =>
              p.toViaEvents(vehicleId, linkId)
            }
            .getOrElse {
              logger.error(
                "Could not find link in matsim network where start and end locations are {} and {} respectively.",
                p.startCoord,
                p.endCoord
              )
              Vector()
            }
      }

      if (viaEvents.isEmpty) viaEvents
      else (departure(vehicleId, viaEvents.head) +: viaEvents) :+ arrival(vehicleId, viaEvents.last)
    }
  }

  def prefixVehicleId(event: BeamPathTraversal): String =
    event.mode + "__" + event.vehicleType + "__" + event.vehicleId

  def createVehicleId(prefix: String, range: PassengerRange): String = {
    range match {
      case PassengerRange.Empty =>
        prefix + "__passenger__0"
      case PassengerRange.Range(start, end) =>
        prefix + "__passenger__" + start + ":" + end
    }
  }

  def insertIdleEvents(idleThresholdInSec: Double, xss: Vector[(String, Vector[ViaEvent])]): Vector[ViaEvent] = {
    case class State(prevTime: Double = 0.0, viaEvents: Vector[ViaEvent] = Vector())
    val state = xss.filter(_._2.nonEmpty).foldLeft(State()) { case (state, (vehicleId, events)) =>
      val event = events.head
      val delta = event.time - state.prevTime
      if (delta < idleThresholdInSec) State(events.last.time, state.viaEvents ++ events)
      else {
        val insertedEvents =
          ViaActivity.start(state.prevTime, vehicleId, event.link, actionName = vehicleId + "__idle") +:
          ViaActivity.end(event.time, vehicleId, event.link, actionName = vehicleId + "__idle") +:
          events
        State(events.last.time, state.viaEvents ++ insertedEvents)
      }
    }
    state.viaEvents
  }
}
