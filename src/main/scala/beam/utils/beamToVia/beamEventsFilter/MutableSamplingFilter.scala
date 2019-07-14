package beam.utils.beamToVia.beamEventsFilter

import beam.utils.beamToVia.beamEvent.BeamEvent

trait MutableSamplingFilter {
  def filter(event: BeamEvent): Unit
  def vehiclesTrips: Traversable[VehicleTrip]
  def personsTrips: Traversable[PersonTrip]
  def personsEvents: Traversable[PersonEvents]
}
