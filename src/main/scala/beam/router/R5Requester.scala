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
import com.typesafe.config.Config
import org.matsim.api.core.v01.{Coord, Id}

// You can run it as:
//  - App with Main method in IntelliJ IDEA. You need to provide config as Program Arguments: `--config test/input/texas/austin-prod-100k.conf`
//  - Run it from gradle: `./gradlew :execute -PmainClass=beam.router.R5Requester -PmaxRAM=4 -PappArgs="['--config', 'test/input/texas/austin-prod-100k.conf']"`
object R5Requester extends BeamHelper {

  private val geo: GeoUtils = new GeoUtils {
    override def localCRS: String = "epsg:2808"
  }

  private val originUTM = geo.wgs2Utm(new Coord(-83.12597352, 42.391160051))
  private val destinationUTM = geo.wgs2Utm(new Coord(-83.037062772, 42.514944839))

  private val baseRoutingRequest: RoutingRequest = {
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
      departureTime = 30600,
      withTransit = true,
      streetVehicles = Vector.empty,
      attributesOfIndividual = Some(personAttribs)
    )
  }

  def main(args: Array[String]): Unit = {
    val (_, cfg) = prepareConfig(args, isConfigArgRequired = true)

    val r5Wrapper = createR5Wrapper(cfg)

    def makeRouteRequest(name: String, request: BeamRouter.RoutingRequest): Unit = {
      val startTimeMillis = System.currentTimeMillis()
      val responce = r5Wrapper.calcRoute(request)
      val endTimeMillis = System.currentTimeMillis()

      val durationSeconds = (endTimeMillis - startTimeMillis) / 1000.0

      println(s"######################## $name ##############################")
      println(s"Trip calculation took: $durationSeconds seconds")
      println(s"Number of routes: ${responce.itineraries.length}")
      responce.itineraries.zipWithIndex.foreach {
        case (route, idx) =>
          println(s"$idx\t$route")
      }
      // println("######################################################" + new String(Array.fill(name.length + 2) { '#' }))
      println
      println
    }

    val carStreetVehicle =
      getStreetVehicle("dummy-car-for-skim-observations", BeamMode.CAV, baseRoutingRequest.originUTM)
    val bikeStreetVehicle =
      getStreetVehicle("dummy-bike-for-skim-observations", BeamMode.BIKE, baseRoutingRequest.originUTM)
    val walkStreetVehicle =
      getStreetVehicle("dummy-body-for-skim-observations", BeamMode.WALK, baseRoutingRequest.originUTM)

    println
    val distance = geo.distUTMInMeters(originUTM, destinationUTM)
    println(s"Trip distance is $distance meters")
    println
    println

    val threeModesReq: RoutingRequest = baseRoutingRequest.copy(
      streetVehicles = Vector(carStreetVehicle, bikeStreetVehicle, walkStreetVehicle),
      withTransit = true
    )

    makeRouteRequest("Three Modes in one shot", threeModesReq)

    val carReq = baseRoutingRequest.copy(streetVehicles = Vector(carStreetVehicle), withTransit = false)
    makeRouteRequest("Only CAR mode", carReq)

    val bikeReq = baseRoutingRequest.copy(streetVehicles = Vector(bikeStreetVehicle), withTransit = false)
    makeRouteRequest("Only BIKE mode", bikeReq)

    val walkReq = baseRoutingRequest.copy(streetVehicles = Vector(walkStreetVehicle), withTransit = true)
    makeRouteRequest("Only WALK mode with transit", walkReq)
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
