package beam.sim.vehiclesharing
import akka.actor.{ActorRef, Props}
import beam.agentsim.agents.Population
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.infrastructure.TAZTreeMap.TAZ
import beam.router.BeamSkimmer
import beam.sim.BeamServices
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm
import beam.utils.FileUtils
import org.matsim.api.core.v01.{Coord, Id}
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

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

private object RandomPointInTAZ {

  def get(taz: TAZ, rand: scala.util.Random): Coord = {
    val radius = Math.sqrt(taz.areaInSquareMeters / Math.PI)
    val a = 2 * Math.PI * rand.nextDouble()
    val r = radius * Math.sqrt(rand.nextDouble())
    val x = r * Math.cos(a)
    val y = r * Math.sin(a)
    new Coord(taz.coord.getX + x, taz.coord.getY + y)
  }
}

case class FixedNonReservingFleetByTAZ(config: SharedFleets$Elm.FixedNonReservingFleetByTaz) extends FleetType {
  override def props(
    beamServices: BeamServices,
    beamSkimmer: BeamSkimmer,
    beamScheduler: ActorRef,
    parkingManager: ActorRef
  ): Props = {
    val initialLocation = mutable.ListBuffer[Coord]()
    val rand = new scala.util.Random(System.currentTimeMillis())
    val res = readCsvFile(config.vehiclesSharePerTAZ)
    if (res.nonEmpty) {
      res.foreach {
        case (idTaz, coord, percentage) =>
          (0 until percentage * config.fleetSize).foreach(
            _ =>
              initialLocation.append(beamServices.tazTreeMap.getTAZ(Id.create(idTaz, classOf[TAZ])) match {
                case Some(taz) => RandomPointInTAZ.get(taz, rand)
                case _         => coord
              })
          )
      }
    } else {
      val ok = config.vehiclesSharePerTAZ.split(",").foldLeft(true) { (res, x) =>
        val y = x.split(":")
        val out = beamServices.tazTreeMap.getTAZ(y(0)) match {
          case Some(taz) =>
            val percentage = y(1).toDouble
            (0 until (percentage * config.fleetSize).toInt)
              .foreach(_ => initialLocation.append(RandomPointInTAZ.get(taz, rand)))
            true
          case _ => false
        }
        res && out
      }
      if (!ok) {
        // fall back here
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
        parkingManager,
        initialLocation,
        vehicleType,
        beamScheduler,
        beamServices,
        beamSkimmer,
        config.maxWalkingDistance,
        Class.forName(config.repositioningClass).asSubclass(classOf[RepositionAlgorithm])
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
        skimmer,
        config.maxWalkingDistance,
        classOf[beam.sim.vehiclesharing.AvailabilityBasedRepositioning]
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
