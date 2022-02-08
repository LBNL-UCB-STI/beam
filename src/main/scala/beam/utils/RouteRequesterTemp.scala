package beam.utils

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.{Location, RoutingRequest, RoutingResponse}
import beam.router.FreeFlowTravelTime
import beam.router.Modes.BeamMode
import beam.router.r5.{DefaultNetworkCoordinator, R5Parameters, R5Wrapper}
import beam.router.skim.urbansim.ODRouterR5GHForActivitySimSkims
import beam.sim.BeamHelper
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.sim.population.{AttributesOfIndividual, HouseholdAttributes}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.api.core.v01.network.Link

import scala.collection.JavaConverters._

object RouteRequesterTemp extends BeamHelper {

//  def main(args: Array[String]): Unit = {
//    val configPath = "production/sfbay/base.conf"
//
//    val manualArgs = Array[String]("--config", configPath)
//    val (_, c) = prepareConfig(manualArgs, isConfigArgRequired = true)
//    val (r5Parameters, networkCoordinator) = R5Parameters.fromConfigWithNetwork(c)
//    implicit val linkIdCoordMap: scala.collection.Map[Id[Link], Coord] = networkCoordinator.network.getLinks.asScala.mapValues(_.getCoord)
//    val router = getR5Router(r5Parameters) // getUniversalODRouter(r5Parameters)
//
//    def doRequest(beamMode: BeamMode, originUTM: Location, destinationUTM: Location): RoutingResponse = {
//      val departureTime = requestTimes.head
//      val originUTMSpaceTime = SpaceTime(originUTM, time = departureTime)
//      val carStreetVehicle = getStreetVehicle("83-1", beamMode, originUTMSpaceTime)
//
//      val request = RoutingRequest(
//        originUTM,
//        destinationUTM,
//        streetVehicles = Vector(carStreetVehicle),
//        withTransit = false,
//        departureTime = departureTime,
//        personId = Some(Id.createPersonId("031400-2014000788156-0-569466")),
//        attributesOfIndividual = Some(personAttributes),
//        triggerId = -1
//      )
//
//      router.calcRoute(request)
//    }
//
//    implicit val geoUtils: GeoUtils = r5Parameters.geo
//
//    /**
//      * theOrigin = {Coord@12161} "[x=566388.1145592784][y=5266570.266006015]"
//      * theDestination = {Coord@12162} "[x=570966.8431022739][y=5268161.458894901]"
//      * from = {Coord@12163} "[x=-122.1176824][y=47.5491444]"
//      * to = {Coord@12164} "[x=-122.0554805][y=47.56298]"
//      */
//    val source = new Location(-122.1473, 37.7461)
//    val destination = new Location(-122.19652, 37.81107)
//    val originUTM: Location = geoUtils.wgs2Utm(source)
//    val destinationUTM: Location = geoUtils.wgs2Utm(destination)
//    val responseCar = doRequest(BeamMode.CAR, originUTM, destinationUTM)
//    val responseBus = doRequest(BeamMode.BUS, originUTM, destinationUTM)
//
//    printLinks(responseCar, source, destination, "CAR")
//    printLinks(responseBus, source, destination, "BUS")
//  }

  def printLinks(response: RoutingResponse, source: Location, destination: Location, msg: String)(implicit geoUtils: GeoUtils, linkIdCoordMap: scala.collection.Map[Id[Link], Coord]): Unit = {
    val coords = for {
      trip <- response.itineraries
      leg  <- trip.beamLegs
      _ = println(leg.mode)
      linkId <- leg.travelPath.linkIds
    } yield (linkId, geoUtils.utm2Wgs(linkIdCoordMap(Id.createLinkId(linkId))))

    val trip = (List(source) ++ coords.map(_._2) ++ List(destination)).map(loc => s"[${loc.getX},${loc.getY}]")
    val links = coords.map(_._1)

    logger.info("msg {}", msg)
    logger.info("trip {}", trip.mkString("[", ", ", "]"))
    logger.info("links {}", links.mkString("[", ", ", "]"))
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
      BeamMode.WALK_TRANSIT,
      BeamMode.BUS
    ),
    valueOfTime = 10.99834490709942,
    age = Some(28),
    income = Some(70000.0)
  )

  private def getUniversalODRouter(r5Parameters: R5Parameters): ODRouterR5GHForActivitySimSkims = {
    ODRouterR5GHForActivitySimSkims(r5Parameters, requestTimes, None)
  }

  private def getR5Router(r5Parameters: R5Parameters): R5Wrapper = {
    new R5Wrapper(
      r5Parameters,
      new FreeFlowTravelTime,
      r5Parameters.beamConfig.beam.routing.r5.travelTimeNoiseFraction
    )
  }

  private def getStreetVehicle(id: String, beamMode: BeamMode, spaceTime: SpaceTime): StreetVehicle = {
    val vehicleTypeId = beamMode match {
      case BeamMode.CAR  => "Car"
      case BeamMode.CAV  => "CAV"
      case BeamMode.BIKE => "FAST-BIKE"
      case BeamMode.WALK => "BODY-TYPE-DEFAULT"
      case BeamMode.BUS => "FREIGHT-1"
      case _             => throw new IllegalStateException(s"Don't know what to do with BeamMode $beamMode")
    }
    StreetVehicle(
      id = Id.createVehicleId(id),
      vehicleTypeId = Id.create(vehicleTypeId, classOf[BeamVehicleType]),
      locationUTM = spaceTime,
      mode = BeamMode.CAR,
      asDriver = true,
      needsToCalculateCost = false
    )
  }

}
