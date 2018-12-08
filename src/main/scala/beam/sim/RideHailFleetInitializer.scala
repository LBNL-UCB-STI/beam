package beam.sim

import java.util.Random
import java.util.stream.Stream

import akka.actor.ActorSystem
import beam.agentsim.agents.ridehail.{RideHailAgent, RideHailManager}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.utils.RandomUtils
import beam.utils.matsim_conversion.ShapeUtils.QuadTreeBounds
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.population.{Activity, Person}
import org.matsim.api.core.v01.{Coord, Id, Scenario}

import scala.collection.JavaConverters._

class RideHailFleetInitializer @Inject()
(scenario: Scenario,
 beamServices: BeamServices,
 actorSystem: ActorSystem) extends LazyLogging {

  def init(): Unit = {
    val quadTreeBounds: QuadTreeBounds = getQuadTreeBound(
      scenario.getPopulation.getPersons
        .values()
        .stream()
    )
    val rand: Random = new Random(beamServices.beamConfig.matsim.modules.global.randomSeed)
    val numRideHailAgents = math.round(
      beamServices.beamConfig.beam.agentsim.numAgents.toDouble *
        beamServices.beamConfig.beam.agentsim.agents.rideHail.numDriversAsFractionOfPopulation
    )
    val persons: Iterable[Person] = RandomUtils.shuffle(scenario.getPopulation.getPersons.values().asScala, rand)
    persons.view.take(numRideHailAgents.toInt).foreach {
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
        val rideHailAgentPersonId: Id[RideHailAgent] =
          Id.create(rideHailName, classOf[RideHailAgent])
        val powertrain = Option(vehicleType.primaryFuelConsumptionInJoulePerMeter)
          .map(new Powertrain(_))
          .getOrElse(Powertrain.PowertrainFromMilesPerGallon(Powertrain.AverageMilesPerGallon))
        val rideHailBeamVehicle = new BeamVehicle(
          id,
          powertrain,
          None,
          vehicleType
        )
    }
  }

  private def getQuadTreeBound[p <: Person](persons: Stream[p]): QuadTreeBounds = {
    var minX: Double = null
    var maxX: Double = null
    var minY: Double = null
    var maxY: Double = null
    persons.forEach { person =>
      val planElementsIterator =
        person.getSelectedPlan.getPlanElements.iterator()
      while (planElementsIterator.hasNext) {
        val planElement = planElementsIterator.next()
        planElement match {
          case activity: Activity =>
            val coord = activity.getCoord
            minX = if (minX == null || minX > coord.getX) coord.getX else minX
            maxX = if (maxX == null || maxX < coord.getX) coord.getX else maxX
            minY = if (minY == null || minY > coord.getY) coord.getY else minY
            maxY = if (maxY == null || maxY < coord.getY) coord.getY else maxY
          case _ =>
        }
      }
    }

    QuadTreeBounds(minX, minY, maxX, maxY)
  }


}
