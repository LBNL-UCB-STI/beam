package beam.sim.vehiclesharing
import akka.actor.{ActorRef, Props}
import beam.agentsim.agents.Population
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm
import com.typesafe.scalalogging.LazyLogging
import org.apache.log4j.Logger
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.JavaConverters._
import scala.collection.mutable

trait VehicleManager

trait FleetType {

  def props(
    beamServices: BeamServices,
    beamScheduler: ActorRef,
    parkingManager: ActorRef
  ): Props
}

case class FixedNonReservingFleetByTAZ(
  managerId: Id[VehicleManager],
  config: SharedFleets$Elm.FixedNonReservingFleetByTaz,
  repConfig: Option[BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm.Reposition]
) extends FleetType
    with LazyLogging {

  case class FixedNonReservingFleetByTAZException(message: String, cause: Throwable = null)
      extends Exception(message, cause)
  override def props(
    beamServices: BeamServices,
    beamScheduler: ActorRef,
    parkingManager: ActorRef
  ): Props = {
    val rand = {
      val seed = beamServices.beamConfig.matsim.modules.global.randomSeed
      new scala.util.Random(seed)
    }
    val initialLocation = mutable.ListBuffer[Coord]()
    config.vehiclesSharePerTAZFromCSV match {
      case Some(fileName) =>
        logger.info(s"Reading shared vehicle fleet from file: $fileName")
        FleetUtils.readCSV(fileName).foreach {
          case (idTaz, coord, share) =>
            val fleetShare: Int = (share * config.fleetSize).toInt
            (0 until fleetShare).foreach(
              _ =>
                initialLocation
                  .append(beamServices.beamScenario.tazTreeMap.getTAZ(Id.create(idTaz, classOf[TAZ])) match {
                    case Some(taz) => TAZTreeMap.randomLocationInTAZ(taz, rand)
                    case _         => coord
                  })
            )
        }
      case _ =>
        logger.info(s"Random distribution of shared vehicle fleet i.e. no file or shares by Taz")
        // fall back to a uniform distribution
        initialLocation.clear()
        val tazArray = beamServices.beamScenario.tazTreeMap.getTAZs.toArray
        (1 to config.fleetSize).foreach { _ =>
          val taz = tazArray(rand.nextInt(tazArray.length))
          initialLocation.prepend(TAZTreeMap.randomLocationInTAZ(taz, rand))
        }
    }

    val vehicleType = beamServices.beamScenario.vehicleTypes.getOrElse(
      Id.create(config.vehicleTypeId, classOf[BeamVehicleType]),
      throw new RuntimeException("Vehicle type id not found: " + config.vehicleTypeId)
    )
    Props(
      new FixedNonReservingFleetManager(
        id = managerId,
        parkingManager = parkingManager,
        locations = initialLocation,
        vehicleType = vehicleType,
        mainScheduler = beamScheduler,
        beamServices = beamServices,
        maxWalkingDistance = config.maxWalkingDistance,
        repositionAlgorithmType = repConfig.map(RepositionAlgorithms.lookup)
      )
    )
  }
}

case class FixedNonReservingFleet(managerId: Id[VehicleManager], config: SharedFleets$Elm.FixedNonReserving)
    extends FleetType {
  override def props(
    beamServices: BeamServices,
    beamScheduler: ActorRef,
    parkingManager: ActorRef
  ): Props = {
    val initialSharedVehicleLocations =
      beamServices.matsimServices.getScenario.getPopulation.getPersons
        .values()
        .asScala
        .map(Population.personInitialLocation)
    val vehicleType = beamServices.beamScenario.vehicleTypes.getOrElse(
      Id.create(config.vehicleTypeId, classOf[BeamVehicleType]),
      throw new RuntimeException("Vehicle type id not found: " + config.vehicleTypeId)
    )
    Props(
      new FixedNonReservingFleetManager(
        managerId,
        parkingManager,
        initialSharedVehicleLocations,
        vehicleType,
        beamScheduler,
        beamServices,
        config.maxWalkingDistance
      )
    )
  }
}

case class InexhaustibleReservingFleet(config: SharedFleets$Elm.InexhaustibleReserving) extends FleetType {
  override def props(
    beamServices: BeamServices,
    beamScheduler: ActorRef,
    parkingManager: ActorRef
  ): Props = {
    val vehicleType = beamServices.beamScenario.vehicleTypes.getOrElse(
      Id.create(config.vehicleTypeId, classOf[BeamVehicleType]),
      throw new RuntimeException("Vehicle type id not found: " + config.vehicleTypeId)
    )
    Props(
      new InexhaustibleReservingFleetManager(
        parkingManager,
        vehicleType,
        beamServices.beamConfig.matsim.modules.global.randomSeed
      )
    )
  }
}
