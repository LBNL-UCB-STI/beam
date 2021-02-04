package beam.router

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.{Location, RoutingRequest}
import beam.router.Modes.BeamMode
import beam.router.graphhopper.GraphHopperWrapper
import beam.router.r5.{R5Parameters, R5Wrapper}
import beam.sim.BeamHelper
import beam.sim.config.BeamConfig
import beam.sim.population.{AttributesOfIndividual, HouseholdAttributes}
import com.conveyal.osmlib.OSM
import com.typesafe.config.Config
import org.matsim.api.core.v01.Id

import java.nio.file.Paths

object GHRequester extends BeamHelper {

  def main(args: Array[String]): Unit = {
    val manualArgs = Array[String]("--config", "test/input/sf-light/sf-light-1k-full-background-activitySim-skims.conf")
    val (_, cfg) = prepareConfig(manualArgs, isConfigArgRequired = true)

    val router = prepareRouter(cfg)

    val carLocation = SpaceTime(x = 546634.319196484, y = 4170177.045038026, time = 62280)
    val carStreetVehicle = getStreetVehicle("dummy-car-for-skim-observations", BeamMode.CAR, carLocation)

    val threeModesReq = baseRoutingRequest.copy(
      streetVehicles = Vector(carStreetVehicle),
      withTransit = false
    )
    val threeModesResp = router.calcRoute(threeModesReq)
    showRouteResponse("car mode response", threeModesResp)
    println
  }

  private val baseRoutingRequest: RoutingRequest = {
    // test from regular sf-light simulation
    // origin [x=549498.8740962344][y=4173165.9625335457]
    // destination [x=549719.0922195059][y=4173537.6159537495]
    // time 19528
    // vehicle WALK

    // [x=546634.319196484][y=4170177.045038026]
    val originUTM = new Location(546634.319196484, 4170177.045038026)
    // [x=543920.6191554248][y=4177487.9300326877]
    val destinationUTM = new Location(543920.6191554248, 4177487.9300326877)
    val personAttribs = AttributesOfIndividual(
      householdAttributes = HouseholdAttributes("48-453-001845-2:117138", 70000.0, 1, 1, 1),
      modalityStyle = None,
      isMale = true,
      availableModes = Seq(BeamMode.CAR, BeamMode.WALK_TRANSIT, BeamMode.BIKE),
      valueOfTime = 17.15686274509804,
      age = None,
      income = Some(70000.0)
    )

    RoutingRequest(
      originUTM = originUTM,
      destinationUTM = destinationUTM,
      departureTime = 62280,
      withTransit = false,
      streetVehicles = Vector.empty,
      personId = None, //Some(Id.createPersonId(1)),
      attributesOfIndividual = Some(personAttribs),
      streetVehiclesUseIntermodalUse = BeamRouter.Access
    )
  }

  private def prepareRouter(cfg: Config): Router = {
    val beamConfig = BeamConfig(cfg)
    val r5Parameters: R5Parameters = R5Parameters.fromConfig(cfg)
    new R5Wrapper(r5Parameters, new FreeFlowTravelTime, travelTimeNoiseFraction = 0)

    val graphHopperDir: String = Paths.get(beamConfig.beam.inputDirectory, "graphhopper").toString
    val ghDir = Paths.get(graphHopperDir, 0.toString).toString
    val carRouter = "staticGH"

    GraphHopperWrapper.createGraphDirectoryFromR5(
      carRouter,
      r5Parameters.transportNetwork,
      new OSM(r5Parameters.beamConfig.beam.routing.r5.osmMapdbFile),
      ghDir,
      Map.empty
    )

    val id2Link: Map[Int, (Location, Location)] = r5Parameters.networkHelper.allLinks
      .map(x => x.getId.toString.toInt -> (x.getFromNode.getCoord -> x.getToNode.getCoord))
      .toMap

    val ghWrapper = new GraphHopperWrapper(
      carRouter,
      ghDir,
      r5Parameters.geo,
      r5Parameters.vehicleTypes,
      r5Parameters.fuelTypePrices,
      Map.empty,
      id2Link
    )

    ghWrapper
  }

  private def showRouteResponse(name: String, threeModesResp: BeamRouter.RoutingResponse): Unit = {
    println(s"######################## $name ##############################")
    println(s"Number of routes: ${threeModesResp.itineraries.length}")
    threeModesResp.itineraries.zipWithIndex.foreach {
      case (route, idx) =>
        println(s"$idx\t$route")
    }
    println("######################################################" + new String(Array.fill(name.length + 2) { '#' }))
  }

  private def getStreetVehicle(id: String, beamMode: BeamMode, spaceTime: SpaceTime): StreetVehicle = {
    val vehicleTypeId = beamMode match {
      case BeamMode.CAR | BeamMode.CAV =>
        "CAV"
      case BeamMode.BIKE =>
        "FAST-BIKE"
      case BeamMode.WALK =>
        "BODY-TYPE-DEFAULT"
      case x =>
        throw new IllegalStateException(s"Don't know what to do with BeamMode $beamMode")
    }
    StreetVehicle(
      id = Id.createVehicleId(id),
      vehicleTypeId = Id.create(vehicleTypeId, classOf[BeamVehicleType]),
      locationUTM = spaceTime,
      mode = beamMode,
      asDriver = true
    )
  }

  private def getStreetVehicle(id: String, beamMode: BeamMode, location: Location): StreetVehicle = {
    val spaceTime = SpaceTime(loc = location, time = 30600)
    getStreetVehicle(id, beamMode, spaceTime)
  }
}
