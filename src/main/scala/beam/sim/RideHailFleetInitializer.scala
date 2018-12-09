package beam.sim

import java.util.Random

import akka.actor.ActorSystem
import beam.agentsim.agents.ridehail.{RideHailAgent, RideHailManager}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.utils.matsim_conversion.ShapeUtils.QuadTreeBounds
import beam.utils.{FileUtils, RandomUtils}
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.population.{Activity, Person, PlanElement}
import org.matsim.api.core.v01.{Coord, Id, Scenario}

import scala.collection.JavaConverters._

class RideHailFleetInitializer @Inject()
(scenario: Scenario,
 beamServices: BeamServices,
 actorSystem: ActorSystem) extends LazyLogging {

  val outputFileBaseName = "ride-hail-fleet"

  //todo where and when to invoke this ?
  def init(): Unit = {
    val quadTreeBounds: QuadTreeBounds = getQuadTreeBound(
      scenario.getPopulation.getPersons
        .values()
        .stream()
        .toArray()
    )
    val rand: Random = new Random(beamServices.beamConfig.matsim.modules.global.randomSeed)
    val numRideHailAgents = math.round(
      beamServices.beamConfig.beam.agentsim.numAgents.toDouble *
        beamServices.beamConfig.beam.agentsim.agents.rideHail.numDriversAsFractionOfPopulation
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
        beamServices.beamConfig.beam.agentsim.agents
        val initialLocation: Coord =
          beamServices.beamConfig.beam.agentsim.agents.rideHail.initialLocation.name match {
            case RideHailManager.INITIAL_RIDE_HAIL_LOCATION_HOME =>
              val radius =
                beamServices.beamConfig.beam.agentsim.agents.rideHail.initialLocation.home.radiusInMeters
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
        val rideHailName = s"rideHailAgent-${person.getId}"
        //CSV data
        val id = BeamVehicle.createId(person.getId, Some("rideHailVehicle"))
        val vehicleTypeId =
          Id.create(beamServices.beamConfig.beam.agentsim.agents.rideHail.vehicleTypeId, classOf[BeamVehicleType])
        val vehicleType = beamServices.vehicleTypes
          .getOrElse(vehicleTypeId, BeamVehicleType.defaultCarBeamVehicleType)
        val rideHailManagerId: Id[RideHailAgent] = Id.create(rideHailName, classOf[RideHailAgent])
        val powertrain = Option(vehicleType.primaryFuelConsumptionInJoulePerMeter)
          .map(new Powertrain(_))
          .getOrElse(Powertrain.PowertrainFromMilesPerGallon(Powertrain.AverageMilesPerGallon))
        val rideHailBeamVehicle = new BeamVehicle(
          id,
          powertrain,
          None,
          vehicleType
        )
        // todo how to generate these ?
        val shift = ""
        val geoFenceX = 0.0
        val geoFenceY = 0.0
        val geoFenceRadius = 0.0
        (id.toString,rideHailManagerId.toString,vehicleType,initialLocation.getX,initialLocation.getY,shift,geoFenceX,geoFenceY,geoFenceRadius)
    }
    //write fleet data to an external csv file
    val filePath = beamServices.matsimServices.getControlerIO.getOutputFilename(outputFileBaseName + ".csv")
    val data = fleetData map { f => f.productIterator mkString ", " } mkString "\n"
    val fileHeader = "id, rideHailManagerId, vehicleType, initialLocationX, initialLocationY, shifts, geoFenceX, geoFenceY, geoFenceRadius"
    FileUtils.writeToFile(filePath,Some(fileHeader),data,None)
  }

  /**
    * Initializes [[beam.agentsim.agents.ridehail.RideHailAgent]] fleet
    * @param beamServices beam services instance
    * @return list of [[beam.agentsim.agents.ridehail.RideHailAgent]] objects
    */
  def generateRideHailFleet(beamServices: BeamServices): List[RideHailAgent] = {
    val filePath = beamServices.matsimServices.getControlerIO.getOutputFilename(outputFileBaseName + ".csv")
    readCSVAsRideHailAgent(filePath)
  }

  /**
    * Reads the ride hail fleet csv as [[beam.agentsim.agents.ridehail.RideHailAgent]] objects
    * @param filePath path to the csv file
    * @return list of [[beam.agentsim.agents.ridehail.RideHailAgent]] objects
    */
  private def readCSVAsRideHailAgent(filePath: String): List[RideHailAgent] = {
    /*todo Read data from csv and generate the RideHailAgent objects.
    todo Ref : https://alvinalexander.com/scala/csv-file-how-to-process-open-read-parse-in-scala*/
    List.empty[RideHailAgent]
  }

  /**
    * Generated the
    * @param persons an array of person objects
    * @tparam p an abstract class that is sub type of [[org.matsim.api.core.v01.population.Person]]
    * @return [[beam.utils.matsim_conversion.ShapeUtils.QuadTreeBounds]] object
    */
  private def getQuadTreeBound[p <: Person](persons: Array[p]): QuadTreeBounds = {
    val coordinates: Seq[Coord] = persons.toList.flatMap(_.getSelectedPlan.getPlanElements.asScala) flatMap {
      case activity: Activity => Some(activity.getCoord)
      case _ => None
    }
    val x_coordinates = coordinates.map(_.getX)
    val y_coordinates = coordinates.map(_.getY)
    QuadTreeBounds(x_coordinates.min,y_coordinates.min,x_coordinates.max,y_coordinates.max)
  }

  type FleetData = (String,String,BeamVehicleType,Double,Double,String,Double,Double,Double)

}
