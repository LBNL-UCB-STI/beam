package beam.sim

import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events._
import org.matsim.api.core.v01.network.{Link, Network}
import org.matsim.api.core.v01.population.Person
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup
import org.matsim.core.router.util.{LinkToLinkTravelTime, TravelTime}
import org.matsim.core.trafficmonitoring.TravelTimeCalculator
import org.matsim.vehicles.Vehicle

class FakeTravelTimeCalculator(network: Network, ttconfigGroup: TravelTimeCalculatorConfigGroup)
    extends TravelTimeCalculator(network, ttconfigGroup) {

  override def getLinkToLinkTravelTime(
    fromLinkId: Id[Link],
    toLinkId: Id[Link],
    time: Double
  ): Double = 0.0

  override def getLinkTravelTimes: TravelTime = (_: Link, _: Double, _: Person, _: Vehicle) => 0.0

  override def getLinkToLinkTravelTimes: LinkToLinkTravelTime = (_: Link, _: Link, _: Double) => 0.0

  override def getLinkTravelTime(link: Link, time: Double): Double = 0.0

  override def handleEvent(e: LinkEnterEvent): Unit = {}

  override def handleEvent(e: LinkLeaveEvent): Unit = {}

  override def handleEvent(event: VehicleAbortsEvent): Unit = {}

  override def handleEvent(event: VehicleArrivesAtFacilityEvent): Unit = {}

  override def handleEvent(event: VehicleEntersTrafficEvent): Unit = {}

  override def handleEvent(event: VehicleLeavesTrafficEvent): Unit = {}
}
