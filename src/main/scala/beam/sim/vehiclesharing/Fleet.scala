package beam.sim.vehiclesharing
import akka.actor.{ActorRef, Props}
import beam.agentsim.agents.Population
import org.matsim.api.core.v01.Scenario
import scala.collection.JavaConverters._

trait FleetType {
  def props(scenario: Scenario, parkingManager: ActorRef): Props
}

case class FixedNonReservingFleet() extends FleetType {
  override def props(scenario: Scenario, parkingManager: ActorRef): Props = {
    val initialSharedVehicleLocations =
      scenario.getPopulation.getPersons
        .values()
        .asScala
        .map(Population.personInitialLocation)
    Props(new FixedNonReservingFleetManager(parkingManager, initialSharedVehicleLocations))
  }
}

case class InexhaustibleReservingFleet() extends FleetType {
  override def props(scenario: Scenario, parkingManager: ActorRef): Props =
    Props(new InexhaustibleReservingFleetManager(parkingManager))
}
