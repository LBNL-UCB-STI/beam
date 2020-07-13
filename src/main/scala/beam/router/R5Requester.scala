package beam.router

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.{Location, RoutingRequest}
import beam.router.Modes.BeamMode
import beam.router.r5.{R5Wrapper, WorkerParameters}
import beam.sim.BeamHelper
import beam.sim.common.GeoUtils
import beam.sim.population.{AttributesOfIndividual, HouseholdAttributes}
import beam.utils.{ProfilingUtils, Statistics}
import beam.utils.map.NewYorkAnalysis.geoUtils
import beam.utils.scenario.PlanElement
import beam.utils.scenario.generic.readers.CsvPlanElementReader
import com.conveyal.r5.profile.{ProfileRequest, StreetMode}
import com.conveyal.r5.streets.{RoutingVisitor, StreetRouter}
import com.typesafe.config.Config
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.mutable.ArrayBuffer

class MyDebugRoutingVisitor extends RoutingVisitor {
  var visitedEdges: Int = 0

  override def visitVertex(state: StreetRouter.State): Unit = {
    val edgeIdx = state.backEdge
    if (edgeIdx != -1) {
      visitedEdges += 1
    }
  }
}

// You can run it as:
//  - App with Main method in IntelliJ IDEA. You need to provide config as Program Arguments: `--config test/input/texas/austin-prod-100k.conf`
//  - Run it from gradle: `./gradlew :execute -PmainClass=beam.router.R5Requester -PmaxRAM=4 -PappArgs="['--config', 'test/input/texas/austin-prod-100k.conf']"`
object R5Requester extends BeamHelper {
  private val geoUtils: GeoUtils = new beam.sim.common.GeoUtils {
    override def localCRS: String = "epsg:2263"
  }

  private val baseRoutingRequest: RoutingRequest = {
    val originUTM = new Location(2961475.272057291, 3623253.4635826824)
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
      destinationUTM = new Location(2967932.9521744307, 3635449.522501624),
      departureTime = 30600,
      withTransit = true,
      streetVehicles = Vector.empty,
      attributesOfIndividual = Some(personAttribs)
    )
  }

  private val shouldLog: Boolean = false

  def main(args: Array[String]): Unit = {
    val (_, cfg) = prepareConfig(args, isConfigArgRequired = true)

    val r5Wrapper = createR5Wrapper(cfg)

    val pathToPlans = "d:/Work/beam/NewYork/results_06-30-2020_21-55-29/sampled_plans.csv.gz"
    val workWithHome = CsvPlanElementReader
      .read(pathToPlans)
      .filter(x => x.planElementType.equalsIgnoreCase("activity"))
      .sliding(2, 2)
      .take(10000)

    val allMs: ArrayBuffer[Double] = ArrayBuffer()
    val carMs: ArrayBuffer[Double] = ArrayBuffer()
    val bikeMs: ArrayBuffer[Double] = ArrayBuffer()
    val walkMs: ArrayBuffer[Double] = ArrayBuffer()

    var emptyCars: Int = 0
    var emptyBikes: Int = 0
    var emptyWalks: Int = 0

    var i: Int = 0

    // http://localhost:8080/plan?fromLat=40.6191753&fromLon=-73.6279716&toLat=41.0000338&toLon=-74.1184985&mode=BICYCLE&full=true

    workWithHome.foreach { arr: Array[PlanElement] =>
      val startWgs = new Coord(arr(0).activityLocationX.get, arr(0).activityLocationY.get)
      val endWgs = new Coord(arr(1).activityLocationX.get, arr(1).activityLocationY.get)
//      val startWgs = new Coord(-73.85971530103826, 40.847741049977)
//      val endWgs = new Coord(-74.3036549956587, 40.66549851415336)
      val start = geoUtils.wgs2Utm(startWgs)
      val end = geoUtils.wgs2Utm(endWgs)

      val carStreetVehicle =
        getStreetVehicle("dummy-car-for-skim-observations", BeamMode.CAV, start)
      val bikeStreetVehicle =
        getStreetVehicle("dummy-bike-for-skim-observations", BeamMode.BIKE, start)
      val walkStreetVehicle =
        getStreetVehicle("dummy-body-for-skim-observations", BeamMode.WALK, start)

      val threeModesReq = baseRoutingRequest.copy(
        streetVehicles = Vector(carStreetVehicle, bikeStreetVehicle, walkStreetVehicle),
        withTransit = true,
        destinationUTM = end
      )
//      allMs += ProfilingUtils.timeWork {
//        val threeModesResp = r5Wrapper.calcRoute(threeModesReq)
//        if (shouldLog) {
//          showRouteResponse("Three Modes in one shot", threeModesResp)
//          println(s"startWgs: ${startWgs}")
//          println(s"endWgs: ${endWgs}")
//          println
//        }
//      }

//      carMs += ProfilingUtils.timeWork {
//        val carReq =
//          baseRoutingRequest.copy(streetVehicles = Vector(carStreetVehicle), withTransit = false, destinationUTM = end)
//        val carResp = r5Wrapper.calcRoute(carReq)
//        if (carResp.itineraries.isEmpty) emptyCars += 1
//
//        if (shouldLog) {
//          showRouteResponse("Only CAR mode", carResp)
//          println(s"startWgs: ${startWgs}")
//          println(s"endWgs: ${endWgs}")
//          println
//        }
//      }

      val bikeElapsedMs = ProfilingUtils.timeWork {
        val bikeReq =
          baseRoutingRequest.copy(streetVehicles = Vector(bikeStreetVehicle), withTransit = false, destinationUTM = end)
        val bikeResp = r5Wrapper.calcRoute(bikeReq)
        if (bikeResp.itineraries.isEmpty) emptyBikes += 1

        if (shouldLog) {
          showRouteResponse("Only BIKE mode", bikeResp)
          println(s"startWgs: ${startWgs}")
          println(s"endWgs: ${endWgs}")
          println
          println
        }
      }
      if (bikeElapsedMs > 10000) {
        val link =
          s"http://localhost:8080/plan?fromLat=${startWgs.getY}&fromLon=${startWgs.getX}&toLat=${endWgs.getY}&toLon=${endWgs.getX}&mode=BICYCLE&full=false"
        println(link)
        println(s"bikeElapsedMs: $bikeElapsedMs")
        println(s"startWgs: ${startWgs}")
        println(s"endWgs: ${endWgs}")
      }
      bikeMs += bikeElapsedMs

//      walkMs += ProfilingUtils.timeWork {
//        val walkReq =
//          baseRoutingRequest.copy(streetVehicles = Vector(walkStreetVehicle), withTransit = true, destinationUTM = end)
//        val walkResp = r5Wrapper.calcRoute(walkReq)
//        if (walkResp.itineraries.isEmpty) emptyWalks += 1
//
//        if (shouldLog) {
//          showRouteResponse("Only WALK mode with transit", walkResp)
//          println
//        }
//      }
      if (i % 100 == 0) {
        println(s"###################### $i ############################")
        println(s"Three Modes in one shot: ${Statistics(allMs)}")
        println(s"Only CAR mode: ${Statistics(carMs)}")
        println(s"Only BIKE mode: ${Statistics(bikeMs)}")
        println(s"Only WALK mode with transit: ${Statistics(walkMs)}")
        println(s"emptyCars: $emptyCars")
        println(s"emptyBikes: $emptyBikes")
        println(s"emptyWalks: $emptyWalks")
        println("######################################################")
      }

      i += 1
    }

    println(s"Three Modes in one shot: ${Statistics(allMs)}")
    println(s"Only CAR mode: ${Statistics(carMs)}")
    println(s"Only BIKE mode: ${Statistics(bikeMs)}")
    println(s"Only WALK mode with transit: ${Statistics(walkMs)}")
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
