package beam.sim.vehiclesharing
import akka.actor.{ActorRef, Props}
import beam.agentsim.agents.Population
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.infrastructure.taz.TAZ
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

//case class FixedNonReservingFleetFromFile(config: SharedFleets$Elm.FixedNonReservingFleetFromFile) extends FleetType {
//  override def props(
//    beamServices: BeamServices,
//    beamSkimmer: BeamSkimmer,
//    beamScheduler: ActorRef,
//    parkingManager: ActorRef
//  ): Props = {
//    val initialLocation = mutable.ListBuffer[Coord]()
//    val rand = new scala.util.Random(System.currentTimeMillis())
//    readCsvFile(config.filePathCSV).foreach {
//      case (idTaz, coord, fleetSize) =>
//        val loc = beamServices.tazTreeMap.getTAZ(Id.create(idTaz, classOf[TAZ])) match {
//          case Some(taz) =>
//            val radius = Math.sqrt(taz.areaInSquareMeters / Math.PI)
//            val a = 2 * Math.PI * rand.nextDouble()
//            val r = radius * Math.sqrt(rand.nextDouble())
//            val x = r * Math.cos(a)
//            val y = r * Math.sin(a)
//            new Coord(taz.coord.getX + x, taz.coord.getY + y)
//          case _ => coord
//        }
//        (0 until fleetSize).foreach(_ => initialLocation.append(loc))
//    }
//
////    val vehicles = mutable.Map.empty[Id[TAZ], (Id[TAZ], Coord, Int)]
////    readCsvFile(config.filePathCSV).foldLeft(()) { (_, x) =>
////      vehicles.put(x._1, x)
////    }
////    val peopleLocation = beamServices.matsimServices.getScenario.getPopulation.getPersons
////      .values()
////      .asScala
////      .map(Population.personInitialLocation)
////    peopleLocation.foreach { loc =>
////      val taz = beamServices.tazTreeMap.getTAZ(loc.getX, loc.getY)
////      if (vehicles.contains(taz.tazId) && vehicles(taz.tazId)._3 > 0) {
////        initialLocation.append(loc)
////        vehicles.update(taz.tazId, vehicles(taz.tazId).copy(_3 = vehicles(taz.tazId)._3 - 1))
////      }
////    }
//
//    val vehicleType = beamServices.vehicleTypes.getOrElse(
//      Id.create(config.vehicleTypeId, classOf[BeamVehicleType]),
//      throw new RuntimeException("Vehicle type id not found: " + config.vehicleTypeId)
//    )
//    Props(
//      new FixedNonReservingFleetManager(
//        parkingManager,
//        initialLocation,
//        vehicleType,
//        beamScheduler,
//        beamServices,
//        beamSkimmer
//      )
//    )
//  }
//
//  private def readCsvFile(filePath: String): Vector[(Id[TAZ], Coord, Int)] = {
//    val mapReader = new CsvMapReader(FileUtils.readerFromFile(filePath), CsvPreference.STANDARD_PREFERENCE)
//    var res = Vector[(Id[TAZ], Coord, Int)]()
//    try {
//      val header = mapReader.getHeader(true)
//      var line: java.util.Map[String, String] = mapReader.read(header: _*)
//      while (null != line) {
//        val idz = line.getOrDefault("idz", "")
//        val x = line.getOrDefault("x", "0.0").toDouble
//        val y = line.getOrDefault("y", "0.0").toDouble
//        val vehicles = line.get("vehicles").toInt
//        res = res :+ (Id.create(idz, classOf[TAZ]), new Coord(x, y), vehicles)
//        line = mapReader.read(header: _*)
//      }
//
//    } finally {
//      if (null != mapReader)
//        mapReader.close()
//    }
//    res
//  }
//}

//case class FixedNonReservingRandomlyDistributedFleet(config: SharedFleets$Elm.FixedNonReservingRandomlyDistributed)
//    extends FleetType {
//  override def props(
//    beamServices: BeamServices,
//    beamSkimmer: BeamSkimmer,
//    beamScheduler: ActorRef,
//    parkingManager: ActorRef
//  ): Props = {
//    val tazArray = beamServices.tazTreeMap.getTAZs.toArray
//    val initialLocation = mutable.ListBuffer[Coord]()
//    val rand = new scala.util.Random(System.currentTimeMillis())
//    (1 to config.fleetSize).foreach(_ => initialLocation.prepend(tazArray(rand.nextInt(tazArray.length)).coord))
//    val vehicleType = beamServices.vehicleTypes.getOrElse(
//      Id.create(config.vehicleTypeId, classOf[BeamVehicleType]),
//      throw new RuntimeException("Vehicle type id not found: " + config.vehicleTypeId)
//    )
//    Props(
//      new FixedNonReservingFleetManager(
//        parkingManager,
//        initialLocation,
//        vehicleType,
//        beamScheduler,
//        beamServices,
//        beamSkimmer
//      )
//    )
//  }
//}

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
