package beam.utils.scripts.austin_network.od

import java.util.concurrent.atomic.AtomicLong

import beam.router.Modes.BeamMode
import beam.utils.scripts.austin_network.{AustinUtils, Logging}
import beam.utils.scripts.austin_network.od.R5Requester.{createR5Wrapper, getRoutingRequest, getStreetVehicle, prepareConfig}
import org.matsim.api.core.v01.Coord

/**
  * Create full skims (expects already as first args:
  * TODO: program args --config pathToConfig
  */
object CreateFullSkims {

  def main(args: Array[String]): Unit = {
    val travelTimeNoiseFraction=0.1
    val tazCentersFilePath = "test\\input\\sf-light\\tazCentersDallas.csv"
    val outputSkimsPath = s"test\\input\\sf-light\\r5-dallas\\fullSkims2.csv"
    val start=0
    val take=Integer.MAX_VALUE


    val (_, cfg) = prepareConfig(args, isConfigArgRequired = true)

    val r5Wrapper = createR5Wrapper(cfg,travelTimeNoiseFraction)

    val lines = AustinUtils.getFileLines(tazCentersFilePath)

    val taz = lines.drop(1).map { line =>
      val cols = line.split(",")
      val tazId = cols(0).replaceAll("\"", "")
      val coord = new Coord(cols(1).toDouble, cols(2).toDouble)
      (tazId, coord)
    }.take(10) //.flatMap(List.fill(4)(_))//.take(1000)

    val odMatrix = for (originTAZ <- taz; destTAZ <- taz) yield (originTAZ, destTAZ)

    val log: Logging = new Logging()
    log.info("start routing")
    var i: AtomicLong = new AtomicLong(0)
    val routes = odMatrix.drop(start).take(take).par.map { case (originTAZ, destTAZ) =>
      val baseRoutingRequest = getRoutingRequest(originTAZ._2, destTAZ._2)
      val carStreetVehicle =
        getStreetVehicle("dummy-car-for-skim-observations", BeamMode.CAV, baseRoutingRequest.originUTM)
      val carReq = baseRoutingRequest.copy(streetVehicles = Vector(carStreetVehicle), withTransit = false)
      val carResp = r5Wrapper.calcRoute(carReq)
      //showRouteResponse("Only CAR mode", carResp)
      val j = i.getAndIncrement()
      if (j % 1000 == 0) {
        log.info(s"routes ready:${j}")
      }

      (originTAZ, destTAZ, carResp)
    }

    val outputSkims = for (route <- routes; hour <- 0 to 23) yield {
      val (originTAZId, _) = route._1
      val (destTAZId, _) = route._2
      val carResp = route._3 //.itineraries.head.beamLegs.head.travelPath
      carResp.itineraries.headOption.map { iternary =>
        val travelPath = iternary.beamLegs.head.travelPath
        s"$hour,CAR,${originTAZId},${destTAZId},${travelPath.duration},0,0,0,${travelPath.distanceInM},0,0,0"
      }
    }

    log.info("end routing")
    AustinUtils.writeFile(outputSkims.flatten.toVector, outputSkimsPath, Some("hour,mode,origTaz,destTaz,travelTimeInS,generalizedTimeInS,cost,generalizedCost,distanceInM,energy,observations,iterations"))
  }

}
