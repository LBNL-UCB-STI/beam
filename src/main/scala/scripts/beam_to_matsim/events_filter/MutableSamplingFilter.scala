package beam.utils.beam_to_matsim.events_filter

import beam.utils.beam_to_matsim.events.BeamEvent

trait MutableSamplingFilter {
  def filter(event: BeamEvent): Unit
  def vehiclesTrips: Traversable[VehicleTrip]
  def personsTrips: Traversable[PersonTrip]
  def personsEvents: Traversable[PersonEvents]
}
