package beam.utils.beam_to_matsim.transit.generator

import beam.utils.beam_to_matsim.events.BeamPathTraversal
import beam.utils.beam_to_matsim.transit.TransitEventsGroup.PassengerRange
import beam.utils.beam_to_matsim.transit.{TransitEventsGroup, TransitHelper}
import beam.utils.beam_to_matsim.via_event.ViaEvent

private[transit] trait TransitViaEventsGeneratorWithLinks extends TransitViaEventsGenerator

private[transit] object CommonViaEventsGeneratorWithLinks extends TransitViaEventsGeneratorWithLinks {
  import TransitHelper._

  override def generator(idleThresholdInSec: Double, rangeSize: Int)(
    events: Vector[BeamPathTraversal]
  ): Vector[ViaEvent] = {
    val groupedEvents = TransitEventsGroup.groupEvents(rangeSize, events)
    val xss = groupedEvents.map { case (range, trips) =>
      val event = trips.head
      val prefix = prefixVehicleId(event)
      prefix -> createViaEvents(trips, createVehicleId(prefix, range))
    }
    insertIdleEvents(idleThresholdInSec, xss)
  }
}

private[transit] object RidehailViaEventsGenerator extends TransitViaEventsGeneratorWithLinks {
  import TransitHelper._

  override def generator(idleThresholdInSec: Double, rangeSize: Int)(
    events: Vector[BeamPathTraversal]
  ): Vector[ViaEvent] = {
    val groupedEvents = TransitEventsGroup.groupEvents(rangeSize, events)
    val xss = groupedEvents.map { case (range, trips) =>
      if (range == PassengerRange.Empty) {
        val rev = trips.reverse
        val (pickup, repositions) = (rev.head, rev.tail)
        val prefix = prefixVehicleId(pickup)
        prefix -> (
          createViaEvents(repositions, prefix + "__reposition") ++
          createViaEvents(Vector(pickup), prefix + "__pickup")
        )
      } else {
        val prefix = prefixVehicleId(trips.head)
        prefix -> createViaEvents(trips, createVehicleId(prefix, range))
      }
    }
    insertIdleEvents(idleThresholdInSec, xss)
  }
}
