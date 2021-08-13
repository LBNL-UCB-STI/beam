package beam.router

import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.population.Person
import org.matsim.core.router.util.TravelTime
import org.matsim.vehicles.Vehicle

class FreeFlowTravelTime extends TravelTime {

  override def getLinkTravelTime(link: Link, time: Double, person: Person, vehicle: Vehicle): Double =
    link.getLength / link.getFreespeed
}
