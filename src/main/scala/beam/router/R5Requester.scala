package beam.router

import java.time.{LocalDateTime, ZoneOffset}

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.{Location, RoutingRequest}
import beam.router.Modes.BeamMode
import beam.router.r5.{R5Parameters, R5Wrapper}
import beam.sim.BeamHelper
import beam.sim.common.GeoUtils
import beam.sim.population.{AttributesOfIndividual, HouseholdAttributes}
import beam.utils.csv.GenericCsvReader
import beam.utils.scenario.generic.readers.CsvPlanElementReader
import beam.utils.scenario.{PersonId, PlanElement}
import beam.utils.{ProfilingUtils, Statistics}
import com.conveyal.r5.streets.{RoutingVisitor, StreetRouter}
import com.typesafe.config.Config
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.mutable.ArrayBuffer
import scala.util.Random

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
//  - Run it from gradle:
/*
./gradlew :execute -PmainClass=beam.router.R5Requester -PmaxRAM=24 -PappArgs="['--config', 'test/input/newyork/new-york-PROD-baseline-one-r5.conf']" \
	-PlogbackCfg=logback.xml -Dplans=test/input/newyork/generic_scenario/1049k-NYC-related/plans.csv.gz -DconvertToWgs=false
 */
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
    val personId = Id.createPersonId(1)
    RoutingRequest(
      originUTM = originUTM,
      destinationUTM = new Location(2967932.9521744307, 3635449.522501624),
      departureTime = 30600,
      withTransit = true,
      streetVehicles = Vector.empty,
      personId = Some(personId),
      attributesOfIndividual = Some(personAttribs)
    )
  }

  private val shouldLog: Boolean = false

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
    val pathToPlans = System.getProperty("plans")
    val shouldConvertToWgs = System.getProperty("convertToWgs").toBoolean
    val nSampleSize = Option(System.getProperty("sampleSize")).map(_.toInt).getOrElse(10000)
    logger.info(s"pathToPlans: $pathToPlans")
    logger.info(s"shouldConvertToWgs: $shouldConvertToWgs")
    logger.info(s"nSampleSize: $nSampleSize")

    val (_, cfg) = prepareConfig(args, isConfigArgRequired = true)

    val r5Wrapper = createR5Wrapper(cfg)

    val workWithHome = CsvPlanElementReader
      .read(pathToPlans)
      .filter(x => x.planElementType.equalsIgnoreCase("activity"))
      .groupBy(x => x.personId.id)
      .toSeq
      .filter {
        case (_, xs) =>
          val firstActivity = xs.lift(0)
          val secondActivity = xs.lift(1)
          val isFirstHome =
            firstActivity.exists(x => x.activityType.exists(actType => actType.equalsIgnoreCase("home")))
          val isSecondWork =
            secondActivity.exists(x => x.activityType.exists(actType => actType.equalsIgnoreCase("work")))
          isFirstHome && isSecondWork
      }
      .map {
        case (personId, xs) =>
          (xs(0), xs(1))
      }

    val sampledWorkWithHome = new Random(42).shuffle(workWithHome).take(nSampleSize)
    logger.info(s"There are ${workWithHome.size} OD pairs (Home -> Work), sampled ${nSampleSize}")

    val allMs: ArrayBuffer[Double] = ArrayBuffer()
    val carMs: ArrayBuffer[Double] = ArrayBuffer()
    val bikeMs: ArrayBuffer[Double] = ArrayBuffer()
    val walkMs: ArrayBuffer[Double] = ArrayBuffer()

    var emptyCars: Int = 0
    var emptyBikes: Int = 0
    var emptyWalks: Int = 0

    var i: Int = 0

    // http://localhost:8080/plan?fromLat=40.6191753&fromLon=-73.6279716&toLat=41.0000338&toLon=-74.1184985&mode=BICYCLE&full=true

    val shouldComputeAllInOne: Boolean = true
    val shouldComputeCar: Boolean = true
    val shouldComputeBike: Boolean = true
    val shouldComputeWalk: Boolean = true

    var nWalkTransits: Int = 0

//    val workWithHome = Array(Array(createActivityPlanElement("3816745", "Home", new Coord(-74.17345197835338, 40.55232541650765), 145),
//      createActivityPlanElement("3816745", "Work", new Coord(-74.0062321680475, 40.76305541131651), 52331)))

    sampledWorkWithHome.foreach {
      case (home, work) =>
        val (startWgs, endWgs, startUTM, endUTM) = if (shouldConvertToWgs) {
          val homeUTM = new Coord(home.activityLocationX.get, home.activityLocationY.get)
          val workUTM = new Coord(work.activityLocationX.get, work.activityLocationY.get)
          val homeWgs = geoUtils.utm2Wgs(homeUTM)
          val workWgs = geoUtils.utm2Wgs(workUTM)
          (homeWgs, workWgs, homeUTM, workUTM)
        } else {
          val homeWgs = new Coord(home.activityLocationX.get, home.activityLocationY.get)
          val workWgs = new Coord(work.activityLocationX.get, work.activityLocationY.get)
          val homeUTM = geoUtils.wgs2Utm(homeWgs)
          val workUTM = geoUtils.wgs2Utm(workWgs)
          (homeWgs, workWgs, homeUTM, workUTM)
        }
        val departureTime = home.activityEndTime.map(_.toInt).getOrElse(30600)
        val carStreetVehicle =
          getStreetVehicle("dummy-car-for-skim-observations", BeamMode.CAV, startUTM, departureTime)

        val bikeStreetVehicle =
          getStreetVehicle("dummy-bike-for-skim-observations", BeamMode.BIKE, startUTM, departureTime)
        val walkStreetVehicle =
          getStreetVehicle("dummy-body-for-skim-observations", BeamMode.WALK, startUTM, departureTime)

        val threeModesReq = baseRoutingRequest.copy(
          streetVehicles = Vector(carStreetVehicle, bikeStreetVehicle, walkStreetVehicle),
          withTransit = true,
          originUTM = startUTM,
          destinationUTM = endUTM,
          departureTime = departureTime
        )

        if (shouldComputeAllInOne) {
          allMs += ProfilingUtils.timeWork {
            val threeModesResp = r5Wrapper.calcRoute(threeModesReq)
            if (shouldLog) {
              showRouteResponse("Three Modes in one shot", threeModesResp)
              showPlanDetails(home, startWgs, endWgs)
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
                originUTM = startUTM,
                destinationUTM = endUTM,
                departureTime = departureTime
              )
            val carResp = r5Wrapper.calcRoute(carReq)
            if (carResp.itineraries.isEmpty) emptyCars += 1

            if (shouldLog) {
              showRouteResponse("Only CAR mode", carResp)
              showPlanDetails(home, startWgs, endWgs)
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
                originUTM = startUTM,
                destinationUTM = endUTM,
                departureTime = departureTime
              )
            val bikeResp = r5Wrapper.calcRoute(bikeReq)
            if (bikeResp.itineraries.isEmpty) emptyBikes += 1

            if (shouldLog) {
              showRouteResponse("Only BIKE mode", bikeResp)
              showPlanDetails(home, startWgs, endWgs)
              println
              println
            }
          }
          if (bikeElapsedMs > 10000) {
            val link =
              s"http://localhost:8080/plan?fromLat=${startWgs.getY}&fromLon=${startWgs.getX}&toLat=${endWgs.getY}&toLon=${endWgs.getX}&mode=BICYCLE&full=false"
            println(link)
            println(s"bikeElapsedMs: $bikeElapsedMs")

            showPlanDetails(home, startWgs, endWgs)
          }
          bikeMs += bikeElapsedMs
        }

        if (shouldComputeWalk) {
          walkMs += ProfilingUtils.timeWork {
            val walkReq =
              baseRoutingRequest.copy(
                streetVehicles = Vector(walkStreetVehicle),
                withTransit = true,
                originUTM = startUTM,
                destinationUTM = endUTM,
                departureTime = departureTime
              )
            val walkResp = r5Wrapper.calcRoute(walkReq)
            if (walkResp.itineraries.isEmpty) emptyWalks += 1
            else {
              walkResp.itineraries.foreach { it =>
                if (it.tripClassifier.isTransit)
                  nWalkTransits += 1
              }
            }

            if (shouldLog) {
              showRouteResponse("Only WALK mode with transit", walkResp)
              showPlanDetails(home, startWgs, endWgs)
              println()
            }
          }
        }

        if (i % 100 == 0) {
          println(s"###################### $i ############################")
          println(s"Three Modes in one shot: ${Statistics(allMs)}")
          println(s"Only CAR mode: ${Statistics(carMs)}")
          println(s"Only BIKE mode: ${Statistics(bikeMs)}")
          println(s"Only WALK mode with transit: ${Statistics(walkMs)}, number of walk transits: ${nWalkTransits}")
          println(s"emptyCars: $emptyCars")
          println(s"emptyBikes: $emptyBikes")
          println(s"emptyWalks: $emptyWalks")
          println("######################################################")
        }

        i += 1
    }

    println(s"Three Modes in one shot: ${Statistics(allMs)}")
    println(s"Only CAR mode: ${Statistics(carMs)}, emptyCars: $emptyCars")
    println(s"Only BIKE mode: ${Statistics(bikeMs)}, $emptyBikes")
    println(s"Only WALK mode with transit: ${Statistics(walkMs)}, $emptyWalks")
  }

  private def showPlanDetails(planElement: PlanElement, startWgs: Location, endWgs: Location): Unit = {
    println(s"personId: ${planElement.personId.id}")
    println(s"activityEndTime: ${planElement.activityEndTime.getOrElse("")}")
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
    val (workerParams: R5Parameters, _) = R5Parameters.fromConfig(cfg)
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
      asDriver = true,
      needsToCalculateCost = beamMode == BeamMode.CAR || beamMode == BeamMode.CAV
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
