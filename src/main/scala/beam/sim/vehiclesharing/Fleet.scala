package beam.sim.vehiclesharing
import akka.actor.{ActorRef, Props}
import beam.agentsim.agents.Population
import beam.agentsim.agents.vehicles.{BeamVehicleType, VehicleManager}
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm
import beam.utils.MathUtils
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.JavaConverters._
import scala.collection.mutable

trait FleetType {
  val vehicleManager: Id[VehicleManager]
  val parkingFilePath: String

  def props(
    beamServices: BeamServices,
    beamScheduler: ActorRef,
    parkingManager: ActorRef
  ): Props
}

case class FixedNonReservingFleetByTAZ(
  vehicleManager: Id[VehicleManager],
  parkingFilePath: String,
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
            val fleetShare: Int = MathUtils.roundUniformly(share * config.fleetSize).toInt
            (0 until fleetShare).foreach(
              _ =>
                initialLocation
                  .append(beamServices.beamScenario.tazTreeMap.getTAZ(Id.create(idTaz, classOf[TAZ])) match {
                    case Some(taz) if coord.getX == 0.0 & coord.getY == 0.0 => TAZTreeMap.randomLocationInTAZ(taz, rand)
                    case _                                                  => coord
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
      Id.create(s"sharedVehicle-${config.vehicleTypeId}", classOf[BeamVehicleType]),
      throw new RuntimeException("Vehicle type id not found: " + s"sharedVehicle-${config.vehicleTypeId}")
    )
    Props(
      new FixedNonReservingFleetManager(
        id = vehicleManager,
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

case class FixedNonReservingFleet(
  vehicleManager: Id[VehicleManager],
  parkingFilePath: String,
  config: SharedFleets$Elm.FixedNonReserving
) extends FleetType {
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
      Id.create("sharedVehicle-" + config.vehicleTypeId, classOf[BeamVehicleType]),
      throw new RuntimeException("Vehicle type id not found: " + config.vehicleTypeId)
    )
    Props(
      new FixedNonReservingFleetManager(
        vehicleManager,
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

case class InexhaustibleReservingFleet(
  vehicleManager: Id[VehicleManager],
  parkingFilePath: String,
  config: SharedFleets$Elm.InexhaustibleReserving,
) extends FleetType {
  override def props(
    beamServices: BeamServices,
    beamScheduler: ActorRef,
    parkingManager: ActorRef
  ): Props = {
    val vehicleType = beamServices.beamScenario.vehicleTypes.getOrElse(
      Id.create("sharedVehicle-" + config.vehicleTypeId, classOf[BeamVehicleType]),
      throw new RuntimeException("Vehicle type id not found: " + config.vehicleTypeId)
    )
    Props(
      new InexhaustibleReservingFleetManager(
        vehicleManager,
        parkingManager,
        vehicleType,
        beamServices.beamConfig.matsim.modules.global.randomSeed,
        beamServices.beamConfig.beam.debug,
      )
    )
  }
}
