package beam.router

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.{Location, RoutingRequest}
import beam.router.Modes.BeamMode
import beam.router.graphhopper.{CarGraphHopperWrapper, GraphHopperWrapper}
import beam.router.r5.{R5Parameters, R5Wrapper}
import beam.router.skim.urbansim.ODRouterR5GHForActivitySimSkims
import beam.sim.BeamHelper
import beam.sim.config.BeamConfig
import beam.sim.population.{AttributesOfIndividual, HouseholdAttributes}
import com.conveyal.osmlib.OSM
import com.typesafe.config.Config
import org.matsim.api.core.v01.Id

import java.io.File
import java.nio.file.Paths
import scala.reflect.io.Directory

object RouteRequester extends BeamHelper {

  def main(args: Array[String]): Unit = {
    val manualArgs = Array[String]("--config", "test/input/sf-light/sf-light-1k-full-background-activitySim-skims.conf")
    val (_, cfg) = prepareConfig(manualArgs, isConfigArgRequired = true)

    val router = getUniversalODRouter(cfg) // getCarGraphHopperWrapper(cfg)

    val departureTime = requestTimes.head
    val originUTM = new Location(543648, 4177464)
    val destinationUTM = new Location(548025, 4182237)

    val originUTMSpaceTime = SpaceTime(originUTM, time = departureTime)
    val carStreetVehicle = getStreetVehicle("83-1", BeamMode.CAR, originUTMSpaceTime)
    val walkStreetVehicle = getStreetVehicle("body-031400-2014000788156-0-569466", BeamMode.WALK, originUTMSpaceTime)

    val request = RoutingRequest(
      originUTM,
      destinationUTM,
      streetVehicles = Vector(carStreetVehicle, walkStreetVehicle),
      withTransit = true,
      departureTime = departureTime,
      personId = Some(Id.createPersonId("031400-2014000788156-0-569466")),
      attributesOfIndividual = Some(personAttributes)
    )

    val resp = router.calcRoute(request, buildDirectCarRoute = true, buildDirectWalkRoute = true)

    showRouteResponse("car mode response", resp)
    println
  }

  val requestTimes: List[Int] = List(30600)

  val personAttributes: AttributesOfIndividual = AttributesOfIndividual(
    householdAttributes = HouseholdAttributes("031400-2014000788156-0", 325147.0, 10, 4, 1),
    modalityStyle = None,
    isMale = true,
    availableModes = Seq(
      BeamMode.CAR,
      BeamMode.CAV,
      BeamMode.WALK,
      BeamMode.BIKE,
      BeamMode.TRANSIT,
      BeamMode.RIDE_HAIL,
      BeamMode.RIDE_HAIL_POOLED,
      BeamMode.RIDE_HAIL_TRANSIT,
      BeamMode.DRIVE_TRANSIT,
      BeamMode.WALK_TRANSIT
    ),
    valueOfTime = 10.99834490709942,
    age = Some(28),
    income = Some(70000.0)
  )

  private def getUniversalODRouter(cfg: Config): ODRouterR5GHForActivitySimSkims = {
    val r5Parameters: R5Parameters = R5Parameters.fromConfig(cfg)
    val odRouter = ODRouterR5GHForActivitySimSkims(r5Parameters, requestTimes, None)
    odRouter
  }

  private def getCarGraphHopperWrapper(cfg: Config): Router = {
    val beamConfig = BeamConfig(cfg)
    val r5Parameters: R5Parameters = R5Parameters.fromConfig(cfg)
    new R5Wrapper(r5Parameters, new FreeFlowTravelTime, travelTimeNoiseFraction = 0)

    val graphHopperDir: String = Paths.get(beamConfig.beam.inputDirectory, "graphhopper").toString
    val ghDir = Paths.get(graphHopperDir, 0.toString).toString
    new Directory(new File(graphHopperDir)).deleteRecursively()

    val carRouter = "staticGH"

    GraphHopperWrapper.createCarGraphDirectoryFromR5(
      carRouter,
      r5Parameters.transportNetwork,
      new OSM(r5Parameters.beamConfig.beam.routing.r5.osmMapdbFile),
      ghDir,
      Map.empty
    )

    val id2Link: Map[Int, (Location, Location)] = r5Parameters.networkHelper.allLinks
      .map(x => x.getId.toString.toInt -> (x.getFromNode.getCoord -> x.getToNode.getCoord))
      .toMap

    val ghWrapper = new CarGraphHopperWrapper(
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

  private def showRouteResponse(
    name: String,
    threeModesResp: BeamRouter.RoutingResponse,
    additionalInfo: Option[String] = None
  ): Unit = {
    println(s"######################## $name ##############################")
    if (additionalInfo.nonEmpty) {
      println(additionalInfo.get)
    }
    println(s"Number of routes: ${threeModesResp.itineraries.length}")
    threeModesResp.itineraries.zipWithIndex.foreach {
      case (route, idx) =>
        println(s"$idx\t$route")
    }
    println("######################################################" + new String(Array.fill(name.length + 2) { '#' }))
  }

  private def getStreetVehicle(id: String, beamMode: BeamMode, spaceTime: SpaceTime): StreetVehicle = {
    val vehicleTypeId = beamMode match {
      case BeamMode.CAR  => "Car"
      case BeamMode.CAV  => "CAV"
      case BeamMode.BIKE => "FAST-BIKE"
      case BeamMode.WALK => "BODY-TYPE-DEFAULT"
      case _             => throw new IllegalStateException(s"Don't know what to do with BeamMode $beamMode")
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
