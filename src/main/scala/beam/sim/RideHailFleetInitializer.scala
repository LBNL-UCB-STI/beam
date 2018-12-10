package beam.sim

import java.io.FileNotFoundException
import java.util.Random

import akka.actor.{ActorRef, ActorSystem}
import beam.agentsim.agents.ridehail.{RideHailAgent, RideHailManager}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.router.osm.TollCalculator
import beam.utils.matsim_conversion.ShapeUtils.QuadTreeBounds
import beam.utils.{FileUtils, RandomUtils}
import com.conveyal.r5.transit.TransportNetwork
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.population.{Activity, Person}
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager

import scala.collection.JavaConverters._
import scala.io.Source

class RideHailFleetInitializer @Inject()
(scenario: Scenario,
 beamServices: BeamServices,
 transportNetwork: TransportNetwork,
 tollCalculator: TollCalculator,
 eventsManager: EventsManager,
 actorSystem: ActorSystem) extends LazyLogging {

  val outputFileBaseName = "ride-hail-fleet"

  //todo learn where and when to invoke this initializer
  def init(): Unit = {
    try {
      val quadTreeBounds: QuadTreeBounds = getQuadTreeBound(
        scenario.getPopulation.getPersons
          .values().asScala
      )
      val rand: Random = new Random(beamServices.beamConfig.matsim.modules.global.randomSeed)
      val numRideHailAgents = math.round(
        beamServices.beamConfig.beam.agentsim.numAgents.toDouble *
          beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.numDriversAsFractionOfPopulation
      )
      val persons: Iterable[Person] = RandomUtils.shuffle(scenario.getPopulation.getPersons.values().asScala, rand)
      val fleetData = persons.view.take(numRideHailAgents.toInt) map {
        person =>
          val personInitialLocation: Coord =
            person.getSelectedPlan.getPlanElements
              .iterator()
              .next()
              .asInstanceOf[Activity]
              .getCoord
          val initialLocation: Coord =
            beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.initialLocation.name match {
              case RideHailManager.INITIAL_RIDE_HAIL_LOCATION_HOME =>
                val radius =
                  beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.initialLocation.home.radiusInMeters
                new Coord(
                  personInitialLocation.getX + radius * (rand.nextDouble() - 0.5),
                  personInitialLocation.getY + radius * (rand.nextDouble() - 0.5)
                )
              case RideHailManager.INITIAL_RIDE_HAIL_LOCATION_UNIFORM_RANDOM =>
                val x = quadTreeBounds.minx + (quadTreeBounds.maxx - quadTreeBounds.minx) * rand
                  .nextDouble()
                val y = quadTreeBounds.miny + (quadTreeBounds.maxy - quadTreeBounds.miny) * rand
                  .nextDouble()
                new Coord(x, y)
              case RideHailManager.INITIAL_RIDE_HAIL_LOCATION_ALL_AT_CENTER =>
                val x = quadTreeBounds.minx + (quadTreeBounds.maxx - quadTreeBounds.minx) / 2
                val y = quadTreeBounds.miny + (quadTreeBounds.maxy - quadTreeBounds.miny) / 2
                new Coord(x, y)
              case RideHailManager.INITIAL_RIDE_HAIL_LOCATION_ALL_IN_CORNER =>
                val x = quadTreeBounds.minx
                val y = quadTreeBounds.miny
                new Coord(x, y)
              case unknown =>
                logger.error(s"unknown rideHail.initialLocation $unknown")
                null
            }
          //CSV data
          val id = BeamVehicle.createId(person.getId, Some("rideHailVehicle"))
          val vehicleTypeId =
            Id.create(beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId, classOf[BeamVehicleType])
          val beamVehicleType = beamServices.vehicleTypes
            .getOrElse(vehicleTypeId, BeamVehicleType.defaultCarBeamVehicleType)
          // todo learn how to generate these values ?
          val shift = ""
          // todo ask if the 'person' is the ride hail manager here ?
          val rideHailManagerId = person.getId.toString
          val geoFenceX = 0.0
          val geoFenceY = 0.0
          val geoFenceRadius = 0.0
          // todo vehicle type is an object , ask what value of it should be written to csv ?
          val vehicleType = beamVehicleType
          //generate fleet data
          FleetData(id.toString, rideHailManagerId, vehicleType.vehicleTypeId, initialLocation.getX, initialLocation.getY, shift, geoFenceX, geoFenceY, geoFenceRadius)
      }
      //write fleet data to an external csv file
      val filePath = beamServices.matsimServices.getControlerIO.getOutputFilename(outputFileBaseName + ".csv")
      val fileHeader = classOf[FleetData].getDeclaredFields.map(_.getName).mkString(", ")
      val data = fleetData map { f => f.productIterator mkString ", " } mkString "\n"
      FileUtils.writeToFile(filePath, Some(fileHeader), data, None)
    } catch {
      case e : Exception =>
        logger.error("Error while initializing ride hail data : " + e.getMessage,e)
    }
  }

  /**
    * Initializes [[beam.agentsim.agents.ridehail.RideHailAgent]] fleet
    * @param beamServices beam services instance
    * @return list of [[beam.agentsim.agents.ridehail.RideHailAgent]] objects
    */
  def generateRideHailFleet(beamServices: BeamServices,scheduler: ActorRef,parkingManager:ActorRef): List[RideHailAgent] = {
    val filePath = beamServices.matsimServices.getControlerIO.getOutputFilename(outputFileBaseName + ".csv")
    readCSVAsRideHailAgent(filePath,scheduler,parkingManager)
  }

  /**
    * Reads the ride hail fleet csv as [[beam.agentsim.agents.ridehail.RideHailAgent]] objects
    * @param filePath path to the csv file
    * @return list of [[beam.agentsim.agents.ridehail.RideHailAgent]] objects
    */
  private def readCSVAsRideHailAgent(filePath: String,scheduler: ActorRef,parkingManager:ActorRef): List[RideHailAgent] = {
    try {
      val bufferedSource = Source.fromFile(filePath)
      val rideHailAgents: Seq[RideHailAgent] = bufferedSource.getLines().toList.drop(1).
        map(s => s.split(", ")).
        flatMap { row =>
          try {
            val fleetData = FleetData(row(0), row(2), row(3), row(4).toDouble, row(5).toDouble, row(6), row(7).toDouble, row(8).toDouble, row(9).toDouble)
            val rideHailAgentId =
              Id.create(s"rideHailAgent-${fleetData.rideHailManagerId}", classOf[RideHailAgent])
            val rideHailBeamVehicleTypeId =
              Id.create(beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId, classOf[BeamVehicleType])
            val rideHailBeamVehicleType = beamServices.vehicleTypes
              .getOrElse(rideHailBeamVehicleTypeId, BeamVehicleType.defaultCarBeamVehicleType)
            val powertrain = Option(rideHailBeamVehicleType.primaryFuelConsumptionInJoulePerMeter)
              .map(new Powertrain(_))
              .getOrElse(Powertrain.PowertrainFromMilesPerGallon(Powertrain.AverageMilesPerGallon))
            val rideHailBeamVehicle = new BeamVehicle(
              Id.create(fleetData.id, classOf[BeamVehicle]),
              powertrain,
              None,
              rideHailBeamVehicleType
            )
            Some(new RideHailAgent(
              rideHailAgentId,
              fleetData.rideHailManagerId,
              scheduler,
              rideHailBeamVehicle,
              new Coord(fleetData.initialLocationX, fleetData.initialLocationY),
              fleetData.shifts.split(";").toList,
              fleetData.geoFenceX,
              fleetData.geoFenceY,
              fleetData.geoFenceRadius,
              eventsManager,
              parkingManager,
              beamServices,
              transportNetwork,
              tollCalculator))
          } catch {
            case e: Exception =>
              logger.error("Error while reading an entry of ride-hail-fleet.csv as RideHailAgent : " + e.getMessage, e)
              None
          }
        }
      rideHailAgents.toList
    } catch {
      case fne : FileNotFoundException =>
        logger.error(s"No file found at path - $filePath",fne)
        List.empty[RideHailAgent]
      case e : Exception =>
        logger.error("Error while reading an entry of ride-hail-fleet.csv as RideHailAgent : " + e.getMessage, e)
        List.empty[RideHailAgent]
    }
  }

  /**
    * Generated the
    * @param persons an array of person objects
    * @return [[beam.utils.matsim_conversion.ShapeUtils.QuadTreeBounds]] object
    */
  private def getQuadTreeBound(persons: Iterable[Person]): QuadTreeBounds = {
    val coordinates: Seq[Coord] = persons.toList.flatMap(_.getSelectedPlan.getPlanElements.asScala) flatMap {
      case activity: Activity => Some(activity.getCoord)
      case _ => None
    }
    val x_coordinates = coordinates.map(_.getX)
    val y_coordinates = coordinates.map(_.getY)
    QuadTreeBounds(x_coordinates.min,y_coordinates.min,x_coordinates.max,y_coordinates.max)
  }

  case class FleetData(id: String,
                       rideHailManagerId: String,
                       vehicleType: String,
                       initialLocationX: Double,
                       initialLocationY: Double,
                       shifts: String,
                       geoFenceX: Double,
                       geoFenceY: Double,
                       geoFenceRadius: Double)

}
