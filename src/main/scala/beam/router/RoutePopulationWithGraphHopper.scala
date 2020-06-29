package beam.router

import java.io.File

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.RoutingRequest
import beam.router.Modes.BeamMode.{CAR, WALK}
import beam.router.graphhopper.GraphHopper
import beam.router.gtfs.FareCalculator
import beam.router.osm.TollCalculator
import beam.router.r5.R5
import beam.sim.BeamHelper
import beam.sim.common.GeoUtilsImpl
import beam.utils.NetworkHelperImpl
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
    val geo = new GeoUtilsImpl(executionConfig.beamConfig)
    val graphHopper = new GraphHopper(graphDir, geo)
    val r5 = new R5(
      R5Parameters(
        executionConfig.beamConfig,
        beamScenario.transportNetwork,
        beamScenario.vehicleTypes,
        beamScenario.fuelTypePrices,
        beamScenario.ptFares,
        geo,
        beamScenario.dates,
        new NetworkHelperImpl(matsimScenario.getNetwork),
        new FareCalculator(executionConfig.beamConfig),
        new TollCalculator(executionConfig.beamConfig)
      ),
      new FreeFlowTravelTime,
      travelTimeNoiseFraction = 0
    )
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
            val request = RoutingRequest(
              originUTM = origin,
              destinationUTM = destination,
              departureTime = time,
              withTransit = false,
              streetVehicles = Vector(
                StreetVehicle(
                  Id.createVehicleId("116378-2"),
                  Id.create("Car", classOf[BeamVehicleType]),
                  new SpaceTime(origin, 0),
                  CAR,
                  asDriver = true
                )
              )
            )
            val ghResponse = graphHopper.calcRoute(request)
            val r5Response = r5.calcRoute(request)
            if (ghResponse.itineraries.isEmpty) {
              if (r5Response.itineraries.nonEmpty) {
                println(r5Response)
              }
            } else {
              val ghTrip = ghResponse.itineraries(0)
              println(ghTrip.legs(0).beamLeg + " " + ghTrip.costEstimate)

              val r5Trip = r5Response.itineraries(0)
              println(r5Trip.legs(0).beamLeg + " " + r5Trip.costEstimate)
            }
            println("<--->")
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
