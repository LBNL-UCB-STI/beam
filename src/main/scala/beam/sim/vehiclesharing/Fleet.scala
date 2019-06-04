package beam.sim.vehiclesharing
import akka.actor.{ActorRef, Props}
import beam.agentsim.agents.Population
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.BeamSkimmer
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm
import beam.utils.FileUtils
import org.apache.log4j.Logger
import org.matsim.api.core.v01.{Coord, Id}
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.collection.JavaConverters._
import scala.collection.mutable

trait VehicleManager

trait FleetType {

  def props(
    beamServices: BeamServices,
    beamSkimmer: BeamSkimmer,
    beamScheduler: ActorRef,
    parkingManager: ActorRef
  ): Props
}

object RandomPointInTAZ {

  def get(taz: TAZ, rand: scala.util.Random): Coord = {
    val radius = Math.sqrt(taz.areaInSquareMeters / Math.PI) / 2
    val a = 2 * Math.PI * rand.nextDouble()
    val r = radius * Math.sqrt(rand.nextDouble())
    val x = r * Math.cos(a)
    val y = r * Math.sin(a)
    new Coord(taz.coord.getX + x, taz.coord.getY + y)
  }
}

case class FixedNonReservingFleetByTAZ(
  managerId: Id[VehicleManager],
  config: SharedFleets$Elm.FixedNonReservingFleetByTaz,
  repConfig: Option[BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm.Reposition]
) extends FleetType {
  private val logger = Logger.getLogger(classOf[FixedNonReservingFleetByTAZ])
  case class FixedNonReservingFleetByTAZException(message: String, cause: Throwable = null)
      extends Exception(message, cause)
  override def props(
    beamServices: BeamServices,
    beamSkimmer: BeamSkimmer,
    beamScheduler: ActorRef,
    parkingManager: ActorRef
  ): Props = {
    val initialLocation = mutable.ListBuffer[Coord]()
    val rand = new scala.util.Random(System.currentTimeMillis())
    config.vehiclesSharePerTAZFromCSV match {
      case Some(fileName) =>
        logger.info(s"Reading shared vehicle fleet from file: $fileName")
        readCsvFile(fileName).foreach {
          case (idTaz, coord, percentage) =>
            (0 until percentage * config.fleetSize).foreach(
              _ =>
                initialLocation.append(beamServices.tazTreeMap.getTAZ(Id.create(idTaz, classOf[TAZ])) match {
                  case Some(taz) => RandomPointInTAZ.get(taz, rand)
                  case _         => coord
                })
            )
        }
      case _ =>
        config.vehiclesSharePerTAZ match {
          case Some(shareStr) =>
            logger.info(s"Distribution of shared fleet by shares according to vehiclesSharePerTAZ: $shareStr")
            shareStr.split(",").foreach { x =>
              val y = x.split(":")
              beamServices.tazTreeMap.getTAZ(y(0)) match {
                case Some(taz) =>
                  val percentage = y(1).toDouble
                  (0 until (percentage * config.fleetSize).toInt)
                    .foreach(_ => initialLocation.append(RandomPointInTAZ.get(taz, rand)))
                case _ =>
                  throw FixedNonReservingFleetByTAZException(
                    s"wrong formats for vehicles shares by taz. review param vehiclesSharePerTAZ => $shareStr"
                  )
              }
            }
          case _ =>
            logger.info(s"Random distribution of shared vehicle fleet i.e. no file or shares by Taz")
            // fall back to a uniform distribution
            initialLocation.clear()
            val tazArray = beamServices.tazTreeMap.getTAZs.toArray
            (1 to config.fleetSize).foreach { _ =>
              val taz = tazArray(rand.nextInt(tazArray.length))
              initialLocation.prepend(RandomPointInTAZ.get(taz, rand))
            }
        }
    }

    val vehicleType = beamServices.vehicleTypes.getOrElse(
      Id.create(config.vehicleTypeId, classOf[BeamVehicleType]),
      throw new RuntimeException("Vehicle type id not found: " + config.vehicleTypeId)
    )
    Props(
      new FixedNonReservingFleetManager(
        managerId,
        parkingManager,
        initialLocation,
        vehicleType,
        beamScheduler,
        beamServices,
        beamSkimmer,
        config.maxWalkingDistance,
        repConfig.map(RepositionAlgorithms.lookup(_))
      )
    )
  }

  private def readCsvFile(filePath: String): Vector[(Id[TAZ], Coord, Int)] = {
    var res = Vector.empty[(Id[TAZ], Coord, Int)]
    var mapReader: CsvMapReader = null
    try {
      mapReader = new CsvMapReader(FileUtils.readerFromFile(filePath), CsvPreference.STANDARD_PREFERENCE)
      val header = mapReader.getHeader(true)
      var line: java.util.Map[String, String] = mapReader.read(header: _*)
      while (null != line) {
        val idz = line.getOrDefault("idz", "")
        val x = line.getOrDefault("x", "0.0").toDouble
        val y = line.getOrDefault("y", "0.0").toDouble
        val vehicles = line.get("vehicles").toInt
        res = res :+ (Id.create(idz, classOf[TAZ]), new Coord(x, y), vehicles)
        line = mapReader.read(header: _*)
      }
    } catch {
      case e: Exception =>
    } finally {
      if (null != mapReader)
        mapReader.close()
    }
    res
  }
}

case class FixedNonReservingFleet(managerId: Id[VehicleManager], config: SharedFleets$Elm.FixedNonReserving)
    extends FleetType {
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
        managerId,
        parkingManager,
        initialSharedVehicleLocations,
        vehicleType,
        beamScheduler,
        beamServices,
        skimmer,
        config.maxWalkingDistance
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
