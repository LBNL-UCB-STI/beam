package beam.sim.vehiclesharing
import akka.actor.{ActorRef, Props}
import beam.agentsim.agents.Population
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.router.BeamSkimmer
import beam.sim.BeamServices
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm
import org.matsim.api.core.v01.{Coord, Id, Scenario}

import scala.collection.JavaConverters._
import scala.collection.mutable

trait FleetType {

  def props(
    beamServices: BeamServices,
    beamSkimmer: BeamSkimmer,
    beamScheduler: ActorRef,
    parkingManager: ActorRef
  ): Props
}

case class FixedNonReservingRandomlyDistributedFleet(config: SharedFleets$Elm.FixedNonReservingRandomlyDistributed)
    extends FleetType {
  override def props(
    beamServices: BeamServices,
    beamSkimmer: BeamSkimmer,
    beamScheduler: ActorRef,
    parkingManager: ActorRef
  ): Props = {
    val tazArray = beamServices.tazTreeMap.getTAZs.toArray
    val initialLocation = mutable.ListBuffer[Coord]()
    val rand = new scala.util.Random(System.currentTimeMillis())
    (1 to config.fleetSize).foreach(_ => initialLocation.prepend(tazArray(rand.nextInt(tazArray.length)).coord))
    val vehicleType = beamServices.vehicleTypes.getOrElse(
      Id.create(config.vehicleTypeId, classOf[BeamVehicleType]),
      throw new RuntimeException("Vehicle type id not found: " + config.vehicleTypeId)
    )
    Props(
      new FixedNonReservingFleetManager(
        parkingManager,
        initialLocation,
        vehicleType,
        beamScheduler,
        beamServices,
        beamSkimmer
      )
    )
  }
}

case class FixedNonReservingFleet(config: SharedFleets$Elm.FixedNonReserving) extends FleetType {
  override def props(
    beamServices: BeamServices,
    skimmer: BeamSkimmer,
    beamScheduler: ActorRef,
    parkingManager: ActorRef
  ): Props = {
    val initialSharedVehicleLocations =
      beamServices.matsimServices.getScenario.getPopulation.getPersons
        .values()
        .asScala
        .map(Population.personInitialLocation)
    val vehicleType = beamServices.vehicleTypes.getOrElse(
      Id.create(config.vehicleTypeId, classOf[BeamVehicleType]),
      throw new RuntimeException("Vehicle type id not found: " + config.vehicleTypeId)
    )
    Props(
      new FixedNonReservingFleetManager(
        parkingManager,
        initialSharedVehicleLocations,
        vehicleType,
        beamScheduler,
        beamServices,
        skimmer
      )
    )
  }
}

case class InexhaustibleReservingFleet(config: SharedFleets$Elm.InexhaustibleReserving) extends FleetType {
  override def props(
    beamServices: BeamServices,
    skimmer: BeamSkimmer,
    beamScheduler: ActorRef,
    parkingManager: ActorRef
  ): Props = {
    val vehicleType = beamServices.vehicleTypes.getOrElse(
      Id.create(config.vehicleTypeId, classOf[BeamVehicleType]),
      throw new RuntimeException("Vehicle type id not found: " + config.vehicleTypeId)
    )
    Props(new InexhaustibleReservingFleetManager(parkingManager, vehicleType))
  }
}
