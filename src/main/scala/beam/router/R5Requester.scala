package beam.router

import java.io.Closeable
import java.nio.file.Path

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.geozone.GeoZoneUtil.toWgsCoordinate
import beam.agentsim.infrastructure.geozone.WgsCoordinate
import beam.router.BeamRouter.{Location, RoutingRequest}
import beam.router.Modes.BeamMode
import beam.router.r5.{R5Wrapper, WorkerParameters}
import beam.sim.BeamHelper
import beam.sim.common.GeoUtils
import beam.sim.population.{AttributesOfIndividual, HouseholdAttributes}
import beam.utils.csv.{CsvWriter, GenericCsvReader}
import com.typesafe.config.Config
import org.matsim.api.core.v01.{Coord, Id}

// You can run it as:
//  - App with Main method in IntelliJ IDEA. You need to provide config as Program Arguments: `--config test/input/texas/austin-prod-100k.conf`
//  - Run it from gradle: `./gradlew :execute -PmainClass=beam.router.R5Requester -PmaxRAM=4 -PappArgs="['--config', 'test/input/texas/austin-prod-100k.conf']"`
object R5Requester extends BeamHelper {

  private val geo: GeoUtils = new GeoUtils {
    override def localCRS: String = "epsg:2808"
  }

  def baseRoutingRequest(originUtm: Coord, destinationUtm: Coord): RoutingRequest = {
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
      originUTM = originUtm,
      destinationUTM = destinationUtm,
      departureTime = 30600,
      withTransit = true,
      streetVehicles = Vector.empty,
      attributesOfIndividual = Some(personAttribs)
    )
  }

  object R5Request {

    def readAsWGS(rec: java.util.Map[String, String]): R5Request = {
      R5Request(
        xFrom = rec.get("xFrom").toDouble,
        xTo = rec.get("xTo").toDouble,
        yFrom = rec.get("yFrom").toDouble,
        yTo = rec.get("yTo").toDouble
      )
    }
  }

  case class R5Request(xFrom: Double, yFrom: Double, xTo: Double, yTo: Double) {
    def originUtm(geo: GeoUtils): Coord = geo.wgs2Utm(new Coord(xFrom, yFrom))
    def destinationUtm(geo: GeoUtils): Coord = geo.wgs2Utm(new Coord(xTo, yTo))
  }

  def readRequestsFromFile(relativePath: String): Set[R5Request] = {
    val (iter: Iterator[R5Request], toClose: Closeable) =
      GenericCsvReader.readAs[R5Request](relativePath, R5Request.readAsWGS, _ => true)
    try {
      iter.toSet
    } finally {
      toClose.close()
    }

  }

  case class RouteRequestResults(
    mode: String,
    number: Int,
    durationSeconds: Double,
    distance: Double,
    responce: BeamRouter.RoutingResponse
  ) {

    def printlns(): Unit = {
      println(s"######################## $mode ##############################")
      println(s"Trip calculation took: $durationSeconds seconds")
      println(s"Distance is: $distance")
      println(s"Number of routes: ${responce.itineraries.length}")
      responce.itineraries.zipWithIndex.foreach {
        case (route, idx) =>
          println(s"$idx\t$route")
      }
      println
      println
    }
  }

  def makeRequestToR5(
    r5Wrapper: R5Wrapper,
    originUtm: Coord,
    destinationUtm: Coord,
    number: Int
  ): Seq[RouteRequestResults] = {
    def makeRouteRequest(name: String, request: BeamRouter.RoutingRequest, distance: Double): RouteRequestResults = {
      val startTimeMillis = System.currentTimeMillis()
      val responce = r5Wrapper.calcRoute(request)
      val endTimeMillis = System.currentTimeMillis()

      val durationSeconds = (endTimeMillis - startTimeMillis) / 1000.0
      RouteRequestResults(name, number, durationSeconds, distance, responce)
    }

    val carStreetVehicle =
      getStreetVehicle("dummy-car-for-skim-observations", BeamMode.CAV, originUtm)
    val bikeStreetVehicle =
      getStreetVehicle("dummy-bike-for-skim-observations", BeamMode.BIKE, originUtm)
    val walkStreetVehicle =
      getStreetVehicle("dummy-body-for-skim-observations", BeamMode.WALK, originUtm)

    val distance = geo.distUTMInMeters(originUtm, destinationUtm)

    //    val threeModesReq: RoutingRequest = baseRoutingRequest(originUtm, destinationUtm).copy(
    //      streetVehicles = Vector(carStreetVehicle, bikeStreetVehicle, walkStreetVehicle),
    //      withTransit = true
    //    )
    //    makeRouteRequest("Three Modes in one shot", threeModesReq)

    val carReq = baseRoutingRequest(originUtm, destinationUtm)
      .copy(streetVehicles = Vector(carStreetVehicle), withTransit = false)
    val car = makeRouteRequest("car", carReq, distance)

    val bikeReq = baseRoutingRequest(originUtm, destinationUtm)
      .copy(streetVehicles = Vector(bikeStreetVehicle), withTransit = false)
    val bike = makeRouteRequest("bike", bikeReq, distance)

    val walkTransitReq = baseRoutingRequest(originUtm, destinationUtm)
      .copy(streetVehicles = Vector(walkStreetVehicle), withTransit = true)
    val walkTransit = makeRouteRequest("walktransit", walkTransitReq, distance)

    val walkReq = baseRoutingRequest(originUtm, destinationUtm)
      .copy(streetVehicles = Vector(walkStreetVehicle), withTransit = false)
    val walk = makeRouteRequest("walk", walkReq, distance)

    Seq(car, bike, walkTransit, walk)
  }

  def main(args: Array[String]): Unit = {
    val (_, cfg) = prepareConfig(args, isConfigArgRequired = true)

    val r5Wrapper = createR5Wrapper(cfg)

    val filePath = "/home/nikolay/.jupyter-files/routes.detroit.2k.csv"
    val csvFilePath = "/home/nikolay/.jupyter-files/routes.detroit.2k.cut.responses.csv"
    val requests = readRequestsFromFile(filePath)

    val csvWriter = new CsvWriter(
      csvFilePath,
      Vector(
        "routeNumber",
        "mode",
        "durationSeconds",
        "distance",
        "numberOfRoutes",
        "responseStr"
      )
    )

    object ResultStats {
      var numberOfSuccessRoutes = 0
      var numberOfEmptyRoutes = 0
    }

    def processResult(result: RouteRequestResults): Unit = {
      val respStr: String = result.responce.itineraries.zipWithIndex
        .map {
          case (route, idx) => s"$idx:$route"
        }
        .mkString
        .replace(',', '.')

      if (result.responce.itineraries.isEmpty) {
        ResultStats.numberOfEmptyRoutes += 1
      } else {
        ResultStats.numberOfSuccessRoutes += 1
      }

      csvWriter.writeRow(
        IndexedSeq(
          result.number,
          result.mode,
          result.durationSeconds,
          result.distance,
          result.responce.itineraries.length,
          "\"" + respStr + "\""
        )
      )
    }

    var number = 0
    def getNumber: Int = {
      number += 1
      number
    }

    val divider = 33
    val oneProgressPiece = requests.size / divider
    var piecesDone = 0
    var numberOfProcessedRequests = 0
    var progressStartTimeMillis = System.currentTimeMillis()
    var progressEndTimeMillis = System.currentTimeMillis()
    def progress(): Unit = {
      numberOfProcessedRequests += 1
      if (numberOfProcessedRequests >= oneProgressPiece) {
        progressEndTimeMillis = System.currentTimeMillis()
        val progressStepSeconds = (progressEndTimeMillis - progressStartTimeMillis) / 1000.0
        progressStartTimeMillis = progressEndTimeMillis
        piecesDone += 1
        numberOfProcessedRequests = 0
        println(s"$piecesDone / $divider done. took $progressStepSeconds seconds")
      }
    }

    println(s"there are ${requests.size} requests and 1/$divider is $oneProgressPiece")

    val startTimeMillis = System.currentTimeMillis()
    requests
      .flatMap { request =>
        progress()
        makeRequestToR5(r5Wrapper, request.originUtm(geo), request.destinationUtm(geo), getNumber)
      }
      .foreach(processResult)
    val endTimeMillis = System.currentTimeMillis()
    val durationSeconds = (endTimeMillis - startTimeMillis) / 1000.0

    csvWriter.close()
    println(s"calculation took $durationSeconds seconds")
    println(s"number of empty routes: ${ResultStats.numberOfEmptyRoutes}")
    println(s"number of success routes: ${ResultStats.numberOfSuccessRoutes}")
    println(s"results are written into $csvFilePath")
  }

  private def createR5Wrapper(cfg: Config): R5Wrapper = {
    val workerParams: WorkerParameters = WorkerParameters.fromConfig(cfg)
    new R5Wrapper(workerParams, new FreeFlowTravelTime, travelTimeNoiseFraction = 0)
  }

  private def getStreetVehicle(id: String, beamMode: BeamMode, location: Location): StreetVehicle = {
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
      locationUTM = SpaceTime(loc = location, time = 30600),
      mode = beamMode,
      asDriver = true
    )
  }
}
