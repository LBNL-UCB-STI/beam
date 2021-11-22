package beam.agentsim.events

import beam.agentsim.agents.vehicles.BeamVehicleType
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.Person
import org.matsim.vehicles.Vehicle

case class EnrouteEvent(
  tick: Double,
  personId: Id[Person],
  vehicleId: Id[Vehicle],
  vehicleType: BeamVehicleType
) extends Event(tick) {
  override def getEventType: String = "EnrouteEvent"
}
