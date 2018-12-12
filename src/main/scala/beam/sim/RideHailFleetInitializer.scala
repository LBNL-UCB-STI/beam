package beam.sim

import java.io.FileNotFoundException

import akka.actor.ActorRef
import beam.agentsim.agents.choice.mode.Range
import beam.agentsim.agents.ridehail.{RideHailAgent, RideHailManager}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.router.osm.TollCalculator
import com.conveyal.r5.transit.TransportNetwork
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.EventsManager

import scala.io.Source

class RideHailFleetInitializer extends LazyLogging {

  //  def init(): Unit = {
  //    try {
  //      val quadTreeBounds: QuadTreeBounds = getQuadTreeBound(
  //        scenario.getPopulation.getPersons
  //          .values()
  //          .asScala
  //      )
  //      val rand: Random = new Random(beamServices.beamConfig.matsim.modules.global.randomSeed)
  //      val numRideHailAgents = math.round(
  //        beamServices.beamConfig.beam.agentsim.numAgents.toDouble *
  //        beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.numDriversAsFractionOfPopulation
  //      )
  //      val persons: Iterable[Person] = RandomUtils.shuffle(scenario.getPopulation.getPersons.values().asScala, rand)
  //      val fleetData = persons.view.take(numRideHailAgents.toInt) map { person =>
  //        val personInitialLocation: Coord =
  //          person.getSelectedPlan.getPlanElements
  //            .iterator()
  //            .next()
  //            .asInstanceOf[Activity]
  //            .getCoord
  //        val initialLocation: Coord =
  //          beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.initialLocation.name match {
  //            case RideHailManager.INITIAL_RIDE_HAIL_LOCATION_HOME =>
  //              val radius =
  //                beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.initialLocation.home.radiusInMeters
  //              new Coord(
  //                personInitialLocation.getX + radius * (rand.nextDouble() - 0.5),
  //                personInitialLocation.getY + radius * (rand.nextDouble() - 0.5)
  //              )
  //            case RideHailManager.INITIAL_RIDE_HAIL_LOCATION_UNIFORM_RANDOM =>
  //              val x = quadTreeBounds.minx + (quadTreeBounds.maxx - quadTreeBounds.minx) * rand
  //                .nextDouble()
  //              val y = quadTreeBounds.miny + (quadTreeBounds.maxy - quadTreeBounds.miny) * rand
  //                .nextDouble()
  //              new Coord(x, y)
  //            case RideHailManager.INITIAL_RIDE_HAIL_LOCATION_ALL_AT_CENTER =>
  //              val x = quadTreeBounds.minx + (quadTreeBounds.maxx - quadTreeBounds.minx) / 2
  //              val y = quadTreeBounds.miny + (quadTreeBounds.maxy - quadTreeBounds.miny) / 2
  //              new Coord(x, y)
  //            case RideHailManager.INITIAL_RIDE_HAIL_LOCATION_ALL_IN_CORNER =>
  //              val x = quadTreeBounds.minx
  //              val y = quadTreeBounds.miny
  //              new Coord(x, y)
  //            case unknown =>
  //              logger.error(s"unknown rideHail.initialLocation $unknown")
  //              null
  //          }
  //        //CSV data
  //        val id = BeamVehicle.createId(person.getId, Some("rideHailVehicle"))
  //        val vehicleTypeId =
  //          Id.create(
  //            beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId,
  //            classOf[BeamVehicleType]
  //          )
  //        val beamVehicleType = beamServices.vehicleTypes
  //          .getOrElse(vehicleTypeId, BeamVehicleType.defaultCarBeamVehicleType)
  //        // todo ask if the 'person' is the ride hail manager here ?
  //        val rideHailManagerId = person.getId.toString
  //        // todo learn how to generate these values ?
  //        val shift = ""
  //        val geoFenceX = 0.0 //tentatively initialized
  //        val geoFenceY = 0.0 //tentatively initialized
  //        val geoFenceRadius = 0.0 //tentatively initialized
  //        // todo vehicle type is an object , ask what value of it should be written to csv ?
  //        val vehicleType = beamVehicleType // tentatively writing vehicleTypeId to CSV
  //        //generate fleet data
  //        FleetData(
  //          id.toString,
  //          rideHailManagerId,
  //          vehicleType.vehicleTypeId,
  //          initialLocation.getX,
  //          initialLocation.getY,
  //          shift,
  //          geoFenceX,
  //          geoFenceY,
  //          geoFenceRadius
  //        )
  //      }
  //      //write fleet data to an external csv file
  //      val filePath = beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.filename
  //      val fileHeader = classOf[FleetData].getDeclaredFields.map(_.getName).mkString(", ")
  //      val data = fleetData map { f =>
  //        f.productIterator mkString ", "
  //      } mkString "\n"
  //      FileUtils.writeToFile(filePath, Some(fileHeader), data, None)
  //    } catch {
  //      case e: Exception =>
  //        logger.error("Error while initializing ride hail data : " + e.getMessage, e)
  //    }
  //  }
  //

  /**
    * Initializes [[beam.agentsim.agents.ridehail.RideHailAgent]] fleet
    * @param beamServices beam services instance
    * @return list of [[beam.agentsim.agents.ridehail.RideHailAgent]] objects
    */
  def init(
    beamServices: BeamServices,
    scheduler: ActorRef,
    parkingManager: ActorRef,
    eventsManager: EventsManager,
    transportNetwork: TransportNetwork,
    tollCalculator: TollCalculator
  ): List[RideHailAgent] = {
    val filePath = beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.filename
    readCSVAsRideHailAgent(
      filePath,
      beamServices,
      scheduler,
      parkingManager,
      eventsManager,
      transportNetwork,
      tollCalculator
    )
  }

  /**
    * Reads the ride hail fleet csv as [[beam.agentsim.agents.ridehail.RideHailAgent]] objects
    * @param filePath path to the csv file
    * @return list of [[beam.agentsim.agents.ridehail.RideHailAgent]] objects
    */
  private def readCSVAsRideHailAgent(
    filePath: String,
    beamServices: BeamServices,
    scheduler: ActorRef,
    parkingManager: ActorRef,
    eventsManager: EventsManager,
    transportNetwork: TransportNetwork,
    tollCalculator: TollCalculator
  ): List[RideHailAgent] = {
    try {
      val bufferedSource = Source.fromFile(filePath)
      val rideHailAgents: Seq[RideHailAgent] =
        bufferedSource.getLines().toList.drop(1).map(s => s.split(",")).flatMap { row =>
          try {
            val fleetData = FleetData(
              row(0),
              row(1),
              row(2),
              row(3).toDouble,
              row(4).toDouble,
              row(5),
              row(6).toDouble,
              row(7).toDouble,
              row(8).toDouble
            )
            val rideHailAgentId = Id.create("RideHailAgent", classOf[RideHailAgent])
            val rideHailManagerId = Id.create(fleetData.rideHailManagerId, classOf[RideHailManager])
            val vehicleTypeId = Id.create(fleetData.vehicleType, classOf[BeamVehicleType])
            val vehicleType =
              beamServices.vehicleTypes.getOrElse(vehicleTypeId, BeamVehicleType.defaultCarBeamVehicleType)
            val powertrain = Option(vehicleType.primaryFuelConsumptionInJoulePerMeter)
              .map(new Powertrain(_))
              .getOrElse(Powertrain.PowertrainFromMilesPerGallon(Powertrain.AverageMilesPerGallon))
            val beamVehicle = new BeamVehicle(
              Id.create(fleetData.id, classOf[BeamVehicle]),
              powertrain,
              None,
              vehicleType
            )
            Some(
              new RideHailAgent(
                rideHailAgentId,
                rideHailManagerId,
                scheduler,
                beamVehicle,
                new Coord(fleetData.initialLocationX, fleetData.initialLocationY),
                Some(generateRanges(fleetData.shifts)),
                Some(fleetData.geoFenceX),
                Some(fleetData.geoFenceY),
                Some(fleetData.geoFenceRadius),
                eventsManager,
                parkingManager,
                beamServices,
                transportNetwork,
                tollCalculator
              )
            )
          } catch {
            case e: Exception =>
              logger.error("Error while reading an entry of ride-hail-fleet.csv as RideHailAgent : " + e.getMessage, e)
              None
          }
        }
      rideHailAgents.toList
    } catch {
      case fne: FileNotFoundException =>
        logger.error(s"No file found at path - $filePath", fne)
        List.empty[RideHailAgent]
      case e: Exception =>
        logger.error("Error while reading an entry of ride-hail-fleet.csv as RideHailAgent : " + e.getMessage, e)
        List.empty[RideHailAgent]
    }
  }

  /**
    * Generates Ranges from the range value as string
    * @param rangesAsString ranges as string value
    * @return List of ranges
    */
  private def generateRanges(rangesAsString: String): List[Range] = {
    val regex = """\{([0-9]+):([0-9]+)\}""".r
    rangesAsString.split(";").toList flatMap {
      case regex(l, u) =>
        try {
          Some(new Range(l.toInt, u.toInt))
        } catch {
          case _: Exception => None
        }
      case _ => None
    }
  }

  /**
    * An intermediary class to hold the ride hail fleet data read from the file.
    * @param id id of the vehicle
    * @param rideHailManagerId id of the ride hail manager
    * @param vehicleType type of the beam vehicle
    * @param initialLocationX x-coordinate of the initial location of the ride hail vehicle
    * @param initialLocationY y-coordinate of the initial location of the ride hail vehicle
    * @param shifts time shifts for the vehicle , usually a stringified collection of time ranges
    * @param geoFenceX x-coordinate of the geo fence
    * @param geoFenceY x-coordinate of the geo fence
    * @param geoFenceRadius radius of the geo fence
    */
  case class FleetData(
    id: String,
    rideHailManagerId: String,
    vehicleType: String,
    initialLocationX: Double,
    initialLocationY: Double,
    shifts: String,
    geoFenceX: Double,
    geoFenceY: Double,
    geoFenceRadius: Double
  )

}
