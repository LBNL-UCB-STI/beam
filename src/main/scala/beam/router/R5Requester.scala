package beam.router

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.{Location, RoutingRequest}
import beam.router.Modes.BeamMode
import beam.router.r5.{R5Parameters, R5Wrapper}
import beam.sim.BeamHelper
import beam.sim.population.{AttributesOfIndividual, HouseholdAttributes}
import com.typesafe.config.Config
import org.matsim.api.core.v01.Id

// You can run it as:
//  - App with Main method in IntelliJ IDEA. You need to provide config as Program Arguments: `--config test/input/texas/austin-prod-100k.conf`
//  - Run it from gradle: `./gradlew :execute -PmainClass=beam.router.R5Requester -PmaxRAM=4 -PappArgs="['--config', 'test/input/texas/austin-prod-100k.conf']"`
object R5Requester extends BeamHelper {

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
      attributesOfIndividual = Some(personAttribs),
      triggerId = -1
    )
  }

  def main(args: Array[String]): Unit = {
    val (_, cfg) = prepareConfig(args, isConfigArgRequired = true)

    val r5Wrapper = createR5Wrapper(cfg)

    val carStreetVehicle =
      getStreetVehicle("dummy-car-for-skim-observations", BeamMode.CAV, baseRoutingRequest.originUTM)
    val bikeStreetVehicle =
      getStreetVehicle("dummy-bike-for-skim-observations", BeamMode.BIKE, baseRoutingRequest.originUTM)
    val walkStreetVehicle =
      getStreetVehicle("dummy-body-for-skim-observations", BeamMode.WALK, baseRoutingRequest.originUTM)

    val threeModesReq = baseRoutingRequest.copy(
      streetVehicles = Vector(carStreetVehicle, bikeStreetVehicle, walkStreetVehicle),
      withTransit = true
    )
    val threeModesResp = r5Wrapper.calcRoute(threeModesReq)
    showRouteResponse("Three Modes in one shot", threeModesResp)
    println

    val carReq = baseRoutingRequest.copy(streetVehicles = Vector(carStreetVehicle), withTransit = false)
    val carResp = r5Wrapper.calcRoute(carReq)
    showRouteResponse("Only CAR mode", carResp)
    println

    val bikeReq = baseRoutingRequest.copy(streetVehicles = Vector(bikeStreetVehicle), withTransit = false)
    val bikeResp = r5Wrapper.calcRoute(bikeReq)
    showRouteResponse("Only BIKE mode", bikeResp)
    println

    val walkReq = baseRoutingRequest.copy(streetVehicles = Vector(walkStreetVehicle), withTransit = true)
    val walkResp = r5Wrapper.calcRoute(walkReq)
    showRouteResponse("Only WALK mode with transit", walkResp)
    println
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
    val workerParams: R5Parameters = R5Parameters.fromConfig(cfg)
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
      asDriver = true,
      needsToCalculateCost = beamMode == BeamMode.CAR || beamMode == BeamMode.CAV
    )
  }
}
