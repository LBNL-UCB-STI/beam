package beam.utils

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.geozone.WgsCoordinate
import beam.router.BeamRouter
import beam.router.BeamRouter.{Location, RoutingRequest, RoutingResponse}
import beam.router.Modes.BeamMode
import beam.router.graphhopper.{CarGraphHopperWrapper, GraphHopperWrapper}
import beam.router.r5.R5Parameters
import beam.router.skim.urbansim.ODRouterR5GHForActivitySimSkims
import beam.sim.BeamHelper
import beam.sim.config.BeamConfig
import beam.sim.population.{AttributesOfIndividual, HouseholdAttributes}
import beam.utils.gtfs.GTFSToShape
import com.conveyal.osmlib.OSM
import com.typesafe.config.Config
import org.matsim.api.core.v01.{Coord, Id}

import java.io.File
import java.nio.file.Paths
import scala.collection.mutable
import scala.io.Source
import scala.reflect.io.Directory
import scala.util.Random

object RouteRequester extends BeamHelper {

  def main(args: Array[String]): Unit = {
    val coordPath = "/mnt/data/work/beam-side/notWorkingGHRoutingODPairs.txt"
    val outputShapeFile = "/mnt/data/work/beam-side/notWorkingGHPairs.shp"
    val configPath = "test/input/sf-light/sf-light-1k-csv.conf"

    val manualArgs = Array[String]("--config", configPath)
    val (_, cfg) = prepareConfig(manualArgs, isConfigArgRequired = true)

    val r5Parameters: R5Parameters = R5Parameters.fromConfig(cfg)
    val router: CarGraphHopperWrapper = getCarGraphHopperWrapper(cfg, r5Parameters) // getUniversalODRouter(cfg) //

    def doRequest(originUTM: Location, destinationUTM: Location): RoutingResponse = {
      val departureTime = requestTimes.head
      val originUTMSpaceTime = SpaceTime(originUTM, time = departureTime)
      val carStreetVehicle = getStreetVehicle("83-1", BeamMode.CAR, originUTMSpaceTime)

      val request = RoutingRequest(
        originUTM,
        destinationUTM,
        streetVehicles = Vector(carStreetVehicle),
        withTransit = false,
        departureTime = departureTime,
        personId = Some(Id.createPersonId("031400-2014000788156-0-569466")),
        attributesOfIndividual = Some(personAttributes),
        triggerId = -1
      )

      router.calcRoute(request, buildDirectCarRoute = true, buildDirectWalkRoute = false)
    }

    val mapEnvelop = r5Parameters.transportNetwork.getEnvelope
    val geoUtils = r5Parameters.geo

    var empty = 0
    var found = 0
    var outside = 0
    var inside = 0
    val pointWithin = scala.collection.mutable.HashSet.empty[Location]
    val pointOutside = scala.collection.mutable.HashSet.empty[Location]

    val source = Source.fromFile(coordPath)
    for (coordRow <- source.getLines()) {
      val Array(ox, oy, dx, dy) = coordRow.split(',').map(x => x.trim.toFloat)
      val originUTM: Location = geoUtils.wgs2Utm(new Coord(ox, oy))
      val destinationUTM: Location = geoUtils.wgs2Utm(new Coord(dx, dy))

      if (mapEnvelop.contains(ox, oy)) {
        pointWithin.add(originUTM)
      } else {
        pointOutside.add(originUTM)
      }
      if (mapEnvelop.contains(dx, dy)) {
        pointWithin.add(destinationUTM)
      } else {
        pointOutside.add(destinationUTM)
      }

      if (mapEnvelop.contains(ox, oy) && mapEnvelop.contains(dx, dy)) {
        inside += 1
        val resp = doRequest(originUTM, destinationUTM)
        if (resp.itineraries.nonEmpty) {
          found += 1
        } else {
          empty += 1
        }
      } else {
        outside += 1
      }
    }
    source.close()

    println(s"Found: $found, empty: $empty, one or both point is outside:$outside, both inside:$inside")
    println(s"Single points inside:${pointWithin.size}, single points outside:${pointOutside.size}")

    found = 0

    val points = pointWithin.toArray
    def randomPoint: Location = points(Random.nextInt(points.length))

    val nonWorkingPoints = scala.collection.mutable.HashSet.empty[Location]

    val numberOfRequests = 10000
    for (_ <- Range(0, numberOfRequests)) {
      val origin = randomPoint
      val destination = randomPoint
      val resp = doRequest(origin, destination)
      if (resp.itineraries.nonEmpty) {
        found += 1
        nonWorkingPoints.remove(origin)
        nonWorkingPoints.remove(destination)
      } else {
        nonWorkingPoints.add(destination)
        nonWorkingPoints.add(origin)
      }
    }

    val twoTimesNotWorkingPoints = scala.collection.mutable.HashSet.empty[Location]
    nonWorkingPoints.foreach { origin =>
      nonWorkingPoints.foreach { destination =>
        val resp = doRequest(origin, destination)
        if (resp.itineraries.nonEmpty) {
          found += 1
          twoTimesNotWorkingPoints.remove(origin)
          twoTimesNotWorkingPoints.remove(destination)
        } else {
          twoTimesNotWorkingPoints.add(destination)
          twoTimesNotWorkingPoints.add(origin)
        }
      }
    }

    println(s"Found $found out of $numberOfRequests")
    println(s"There are ${twoTimesNotWorkingPoints.size} points outside of GH reach")

    val wgsCoords: mutable.Set[WgsCoordinate] = twoTimesNotWorkingPoints.map { point =>
      val wgsCoord = geoUtils.utm2Wgs(point)
      new WgsCoordinate(wgsCoord.getY, wgsCoord.getX)
    }

    GTFSToShape.coordsToShapefile(wgsCoords, outputShapeFile)
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

  private def getUniversalODRouter(r5Parameters: R5Parameters): ODRouterR5GHForActivitySimSkims = {
    val odRouter = ODRouterR5GHForActivitySimSkims(r5Parameters, requestTimes, None)
    odRouter
  }

  private def getCarGraphHopperWrapper(cfg: Config, r5Parameters: R5Parameters): CarGraphHopperWrapper = {
    val beamConfig = BeamConfig(cfg)

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
      asDriver = true,
      needsToCalculateCost = false
    )
  }

  private def getStreetVehicle(id: String, beamMode: BeamMode, location: Location): StreetVehicle = {
    val spaceTime = SpaceTime(loc = location, time = 30600)
    getStreetVehicle(id, beamMode, spaceTime)
  }
}
