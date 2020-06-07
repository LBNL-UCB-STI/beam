package beam.router

import java.io.File

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.RoutingRequest
import beam.router.Modes.BeamMode.{CAR, WALK}
import beam.router.graphhopper.GraphHopper
import beam.sim.BeamHelper
import beam.sim.common.GeoUtilsImpl
import com.conveyal.osmlib.OSM
import org.matsim.api.core.v01.population.{Activity, Plan}
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.JavaConverters._

object RoutePopulationWithGraphHopper extends BeamHelper {

  def main(args: Array[String]): Unit = {
    val (_, config) = prepareConfig(args, isConfigArgRequired = true)
    val executionConfig = setupBeamWithConfig(config)
    val (matsimScenario, beamScenario) =
      buildMatsimScenarioAndBeamScenario(executionConfig.beamConfig, executionConfig.matsimConfig)
    val graphDir = executionConfig.beamConfig.beam.inputDirectory + "/graphhopper"
    if (!new File(graphDir).exists())
      GraphHopper.createGraphDirectoryFromR5(
        beamScenario.transportNetwork,
        new OSM(executionConfig.beamConfig.beam.inputDirectory + "/r5/osm.mapdb"),
        graphDir
      )
    val graphHopper = new GraphHopper(graphDir, new GeoUtilsImpl(executionConfig.beamConfig))
    val startTime = System.currentTimeMillis()
    matsimScenario.getPopulation.getPersons
      .values()
      .forEach(person => {
        activities(person.getSelectedPlan)
          .sliding(2)
          .foreach(pair => {
            val origin = pair(0).getCoord
            val destination = pair(1).getCoord
            val time = pair(0).getEndTime.toInt
            val response = graphHopper.calcRoute(
              RoutingRequest(
                originUTM = origin,
                destinationUTM = destination,
                departureTime = time,
                withTransit = true,
                streetVehicles = Vector(
                  StreetVehicle(
                    Id.createVehicleId("116378-2"),
                    Id.create("Car", classOf[BeamVehicleType]),
                    new SpaceTime(origin, 0),
                    CAR,
                    asDriver = true
                  ),
                  StreetVehicle(
                    Id.createVehicleId("body-116378-2"),
                    Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
                    new SpaceTime(new Coord(origin.getX, origin.getY), time),
                    WALK,
                    asDriver = true
                  )
                )
              )
            )
            println(response)
          })
      })
    println(System.currentTimeMillis() - startTime)
  }

  def activities(plan: Plan): Vector[Activity] = {
    plan.getPlanElements.asScala
      .filter(_.isInstanceOf[Activity])
      .map(_.asInstanceOf[Activity])
      .toVector
  }

}
