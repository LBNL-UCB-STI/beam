package beam.utils.scripts.austin_network.od

import java.util.concurrent.atomic.AtomicLong

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.{BeamRouter, FreeFlowTravelTime}
import beam.router.BeamRouter.{Location, RoutingRequest}
import beam.router.Modes.BeamMode
import beam.router.r5.{R5Wrapper, WorkerParameters}
import beam.sim.BeamHelper
import beam.sim.population.{AttributesOfIndividual, HouseholdAttributes}
import beam.utils.scripts.austin_network.{AustinUtils, Logging}
import com.typesafe.config.Config
import org.matsim.api.core.v01.{Coord, Id}

// You can run it as:
//  - App with Main method in IntelliJ IDEA. You need to provide config as Program Arguments: `--config test/input/texas/austin-prod-100k.conf`
//  - Run it from gradle: `./gradlew :execute -PmainClass=beam.router.R5Requester -PmaxRAM=4 -PappArgs="['--config', 'test/input/texas/austin-prod-100k.conf']"`
object R5Requester extends BeamHelper {

  val travelTimeNoiseFraction=0.75


  def getRoutingRequest(origin:Coord,dest:Coord): RoutingRequest = {
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
      originUTM = origin,
      destinationUTM = dest,
      departureTime = 30600,
      withTransit = false,
      streetVehicles = Vector.empty,
      attributesOfIndividual = Some(personAttribs)
    )
  }


  def main(args: Array[String]): Unit = {

    val (_, cfg) = prepareConfig(args, isConfigArgRequired = true)

    val r5Wrapper = createR5Wrapper(cfg)

    val lines=AustinUtils.getFileLines("test\\input\\sf-light\\r5-temp\\uber-speed-od-table.csv")

    val odMatrix= lines.drop(1).map{line =>
      val cols=line.split(",")
      val rowId=cols(0).replaceAll("\"","")
      val origin=AustinUtils.getGeoUtils.wgs2Utm(new Coord(cols(3).toDouble,cols(4).toDouble))
      val dest=AustinUtils.getGeoUtils.wgs2Utm(new Coord(cols(6).toDouble,cols(7).toDouble))
      (rowId,origin,dest)
    }.flatMap(List.fill(4)(_))//.take(1000)

    val log:Logging=new Logging()
    log.info("start routing")
    var i:AtomicLong=new AtomicLong(0)
    val result=odMatrix.par.map{ case  (rowId,origin,dest) =>
     val baseRoutingRequest= getRoutingRequest(origin,dest)
      val carStreetVehicle =
        getStreetVehicle("dummy-car-for-skim-observations", BeamMode.CAV, baseRoutingRequest.originUTM)
      val carReq = baseRoutingRequest.copy(streetVehicles = Vector(carStreetVehicle), withTransit = false)
      val carResp = r5Wrapper.calcRoute(carReq)
      //showRouteResponse("Only CAR mode", carResp)
      val j=i.getAndIncrement()
      if (j%1000==0){
        log.info(s"routes ready:${j}")
      }


      carResp.itineraries.headOption.map{ iternary =>
        s"$rowId,${iternary.legs.head.beamLeg.travelPath.linkIds.size},${iternary.legs.head.beamLeg.travelPath.duration},${iternary.legs.head.beamLeg.travelPath.linkIds.mkString("-")}"
      }
    }.flatten.toVector
    log.info("end routing")
    AustinUtils.writeFile(result,s"test\\input\\sf-light\\r5-temp\\odRoutes-${travelTimeNoiseFraction}.csv",Some("rowId,numLinks,travelTimeInSec,linkIds"))
  }

  private def showRouteResponse(name: String, threeModesResp: BeamRouter.RoutingResponse) = {
    println(s"######################## $name ##############################")
    println(s"Number of routes: ${threeModesResp.itineraries.length}")
    threeModesResp.itineraries.zipWithIndex.foreach {
      case (route, idx) =>
        println(s"$idx\t$route")
    }
    println("######################################################" + new String(Array.fill(name.length + 2) { '#' }))
  }

  def createR5Wrapper(cfg: Config): R5Wrapper = {
    val workerParams: WorkerParameters = WorkerParameters.fromConfig(cfg)
    new R5Wrapper(workerParams, new FreeFlowTravelTime, travelTimeNoiseFraction = travelTimeNoiseFraction)
  }

  def getStreetVehicle(id: String, beamMode: BeamMode, location: Location): StreetVehicle = {
    val vehicleTypeId = beamMode match {
      case BeamMode.CAR | BeamMode.CAV =>
        "CAV"
      case BeamMode.BIKE =>
        "FAST-BIKE"
      case BeamMode.WALK =>
        "BODY-TYPE-DEFAULT"
      case x =>
        throw new IllegalStateException(s"Don't know what to do with BeamMode ${beamMode}")
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
