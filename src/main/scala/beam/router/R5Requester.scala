package beam.router

import java.time.{LocalDateTime, ZoneOffset}

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.{Location, RoutingRequest, RoutingResponse}
import beam.router.Modes.BeamMode
import beam.router.r5.{R5Parameters, R5Wrapper}
import beam.sim.BeamHelper
import beam.sim.common.GeoUtils
import beam.sim.population.{AttributesOfIndividual, HouseholdAttributes}
import beam.utils.csv.GenericCsvReader
import beam.utils.scenario.{PersonId, PlanElement}
import beam.utils.scenario.generic.readers.CsvPlanElementReader
import beam.utils.{ProfilingUtils, Statistics}
import com.conveyal.r5.streets.{RoutingVisitor, StreetRouter}
import com.typesafe.config.Config
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.JavaConverters._
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
    override def localCRS: String = "epsg:32118"
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

  private val shouldLog: Boolean = true

  def readPersonIds(pathToOnlyWalk: String): Set[String] = {
    def mapper(map: java.util.Map[String, String]): String = {
      val personId = map.get("person")
      val stopIdx = personId.lastIndexOf(".")
      if (stopIdx > 0) personId.substring(0, stopIdx)
      else personId
    }
    val (it, toClose) = GenericCsvReader.readAs[String](pathToOnlyWalk, mapper, _ => true)
    try {
      it.toSet
    } finally {
      toClose.close()
    }
  }

  def main(args: Array[String]): Unit = {
    val (_, cfg) = prepareConfig(args, isConfigArgRequired = true)

    val r5Wrapper = createR5Wrapper(cfg)
    val personIds: Set[String] = readPersonIds("C:/Users/User/Downloads/onlywalk.csv")
    val pathToPlans = "C:/Users/User/Downloads/plans.csv.gz"
//    val workWithHome = CsvPlanElementReader
//      .read(pathToPlans)
//      .filter(x => x.planElementType.equalsIgnoreCase("activity") && personIds.contains(x.personId.id))
//      .groupBy(x => x.personId.id)
//      .flatMap {
//        case (personId, xs) =>
//          xs.sliding(2, 1)
//      }
//      .filter(_.length == 2)

    val allMs: ArrayBuffer[Double] = ArrayBuffer()
    val carMs: ArrayBuffer[Double] = ArrayBuffer()
    val bikeMs: ArrayBuffer[Double] = ArrayBuffer()
    val walkMs: ArrayBuffer[Double] = ArrayBuffer()

    var emptyCars: Int = 0
    var emptyBikes: Int = 0
    var emptyWalks: Int = 0

    var i: Int = 0

    // http://localhost:8080/plan?fromLat=40.6191753&fromLon=-73.6279716&toLat=41.0000338&toLon=-74.1184985&mode=BICYCLE&full=true

    var totalTransits: Int = 0

    val shouldComputeAllInOne: Boolean = false
    val shouldComputeCar: Boolean = false
    val shouldComputeBike: Boolean = false
    val shouldComputeWalk: Boolean = true

    val walkTransits: ArrayBuffer[RoutingResponse] = ArrayBuffer()

    val (pteIter, toClose2) = GenericCsvReader.readAs[Map[String, String]](
      "C:/Users/User/Downloads/pte_from_walkers.csv",
      mapper => mapper.asScala.toMap,
      x => true
    )
    val ptes = try { pteIter.toArray } finally { toClose2.close() }

    val workWithHome = ptes.map { pte =>
      val startWgsCoord = new Coord(pte("startX").toDouble, pte("startY").toDouble)
      val endWgsCoord = new Coord(pte("endX").toDouble, pte("endY").toDouble)
      val departureTime = pte("departureTime").toDouble
      Array(
        createActivityPlanElement("3816745", "Home", startWgsCoord, departureTime),
        createActivityPlanElement("3816745", "Work", endWgsCoord, departureTime)
      )
    }

//    val workWithHome = Array(Array(createActivityPlanElement("3816745", "Home", new Coord(-74.17345197835338, 40.55232541650765), 145),
//      createActivityPlanElement("3816745", "Work", new Coord(-74.0062321680475, 40.76305541131651), 52331)))

    workWithHome.foreach { arr: Array[PlanElement] =>
      val startUTM = new Coord(arr(0).activityLocationX.get, arr(0).activityLocationY.get)
      val endUTM = new Coord(arr(1).activityLocationX.get, arr(1).activityLocationY.get)

      val startWgs = geoUtils.utm2Wgs(startUTM)
      val endWgs = geoUtils.utm2Wgs(endUTM)
//      val startWgs = new Coord(-73.85971530103826, 40.847741049977)
//      val endWgs = new Coord(-74.3036549956587, 40.66549851415336)

      val departureTime = arr(0).activityEndTime.map(_.toInt).getOrElse(30600)
      val carStreetVehicle =
        getStreetVehicle("dummy-car-for-skim-observations", BeamMode.CAV, startUTM, departureTime)

      val bikeStreetVehicle =
        getStreetVehicle("dummy-bike-for-skim-observations", BeamMode.BIKE, startUTM, departureTime)
      val walkStreetVehicle =
        getStreetVehicle("dummy-body-for-skim-observations", BeamMode.WALK, startUTM, departureTime)

      val threeModesReq = baseRoutingRequest.copy(
        streetVehicles = Vector(carStreetVehicle, bikeStreetVehicle, walkStreetVehicle),
        withTransit = true,
        destinationUTM = endUTM,
        departureTime = departureTime
      )

      if (shouldComputeAllInOne) {
        allMs += ProfilingUtils.timeWork {
          val threeModesResp = r5Wrapper.calcRoute(threeModesReq)
          if (shouldLog) {
            showRouteResponse("Three Modes in one shot", threeModesResp)
            showPlanDetails(arr, startWgs, endWgs)
            println
          }
        }
      }

      if (shouldComputeCar) {
        carMs += ProfilingUtils.timeWork {
          val carReq =
            baseRoutingRequest.copy(
              streetVehicles = Vector(carStreetVehicle),
              withTransit = false,
              destinationUTM = endUTM,
              departureTime = departureTime
            )
          val carResp = r5Wrapper.calcRoute(carReq)
          if (carResp.itineraries.isEmpty) emptyCars += 1

          if (shouldLog) {
            showRouteResponse("Only CAR mode", carResp)
            showPlanDetails(arr, startWgs, endWgs)
            println
          }
        }
      }

      if (shouldComputeBike) {
        val bikeElapsedMs = ProfilingUtils.timeWork {
          val bikeReq =
            baseRoutingRequest.copy(
              streetVehicles = Vector(bikeStreetVehicle),
              withTransit = false,
              destinationUTM = endUTM,
              departureTime = departureTime
            )
          val bikeResp = r5Wrapper.calcRoute(bikeReq)
          if (bikeResp.itineraries.isEmpty) emptyBikes += 1

          if (shouldLog) {
            showRouteResponse("Only BIKE mode", bikeResp)
            showPlanDetails(arr, startWgs, endWgs)
            println
            println
          }
        }
        if (bikeElapsedMs > 10000) {
          val link =
            s"http://localhost:8080/plan?fromLat=${startWgs.getY}&fromLon=${startWgs.getX}&toLat=${endWgs.getY}&toLon=${endWgs.getX}&mode=BICYCLE&full=false"
          println(link)
          println(s"bikeElapsedMs: $bikeElapsedMs")

          showPlanDetails(arr, startWgs, endWgs)
        }
        bikeMs += bikeElapsedMs
      }

      if (shouldComputeWalk) {
        walkMs += ProfilingUtils.timeWork {
          val walkReq =
            baseRoutingRequest.copy(
              streetVehicles = Vector(walkStreetVehicle),
              withTransit = true,
              destinationUTM = endUTM,
              departureTime = departureTime
            )
          val walkResp = r5Wrapper.calcRoute(walkReq)
          if (walkResp.itineraries.isEmpty) emptyWalks += 1
          else {
            walkResp.itineraries.foreach { it =>
              if (it.tripClassifier.isTransit)
                walkTransits += walkResp
            }
          }

          if (shouldLog) {
            showRouteResponse("Only WALK mode with transit", walkResp)
            showPlanDetails(arr, startWgs, endWgs)
            println()
          }
        }
      }

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

  private def showPlanDetails(arr: Array[PlanElement], startWgs: Location, endWgs: Location) = {
    println(s"personId: ${arr(0).personId.id}")
    println(s"activityEndTime: ${arr(0).activityEndTime.getOrElse("")}")
    println(s"startWgs: ${startWgs}")
    println(s"endWgs: ${endWgs}")
    println(s"google link: ${googleLink(startWgs, endWgs, LocalDateTime.of(2020, 10, 7, 10, 15))}")
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

  private def googleLink(startWgs: Coord, endWgs: Coord, date: LocalDateTime): String = {
    val s = s"https://www.google.com/maps/dir/${startWgs.getY}%09${startWgs.getX}/${endWgs.getY}%09${endWgs.getX}"
    val timeEpochSeconds = date.toEpochSecond(ZoneOffset.UTC)
    s + s"/data=!3m1!4b1!4m15!4m14!1m3!2m2!1d-97.7584165!2d30.3694661!1m3!2m2!1d-97.7295503!2d30.329807!2m4!2b1!6e0!7e2!8j${timeEpochSeconds}!3e0"
  }

  private def createR5Wrapper(cfg: Config): R5Wrapper = {
    val workerParams: R5Parameters = R5Parameters.fromConfig(cfg)
    new R5Wrapper(workerParams, new FreeFlowTravelTime, travelTimeNoiseFraction = 0)
  }

  private def getStreetVehicle(
    id: String,
    beamMode: BeamMode,
    location: Location,
    departureTime: Int = 30600
  ): StreetVehicle = {
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
      locationUTM = SpaceTime(loc = location, time = departureTime),
      mode = beamMode,
      asDriver = true
    )
  }

  def createActivityPlanElement(
    personId: String,
    activityType: String,
    wgsCoord: Coord,
    endTime: Double
  ): PlanElement = {
    val utmCoord = geoUtils.wgs2Utm(wgsCoord)
    PlanElement(
      personId = PersonId(personId),
      0,
      0,
      true,
      "Activity",
      0,
      Some(activityType),
      Some(utmCoord.getX),
      Some(utmCoord.getY),
      Some(endTime),
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      Seq.empty,
      None
    )

  }
}
