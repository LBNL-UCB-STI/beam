package beam.agentsim.infrastructure.parking

import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.ZoneSearchTree
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.sim.common.SimpleGeoUtils
import beam.utils.FileUtils
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.Coord
import play.api.libs.json.{JsArray, JsObject, Json}

import scala.util.Random

/**
  * @author Dmitry Openkov
  */
object GenerateSFBayHubsApp extends App with StrictLogging {

  def parseArgs(args: Array[String]) = {
    args
      .sliding(2, 2)
      .toList
      .collect {
        case Array("--station-info", filePath: String) => ("station-info", filePath)
        case Array("--taz-centers", filePath: String)  => ("taz-centers", filePath)
        case Array("--out", filePath: String)          => ("out", filePath)
        case arg @ _ =>
          throw new IllegalArgumentException(arg.mkString(" "))
      }
      .toMap
  }

  val argsMap = parseArgs(args)

  if (argsMap.size != 3) {
    println(
      "Usage:" +
      " --station-info ~/Downloads/station_information.json" +
      " --taz-centers test/input/beamville/taz-centers.csv" +
      " --out test/input/beamville/parking/link-parking.csv"
    )
    System.exit(1)
  }
  logger.info("args = {}", argsMap)

  val tazMap = TAZTreeMap.getTazTreeMap(argsMap("taz-centers"))

  val stations = FileUtils.using(FileUtils.getInputStream(argsMap("station-info"))) { is =>
    val stationInfo = Json.parse(is).as[JsObject]
    val stations = (stationInfo \ "data" \ "stations").as[JsArray].value
    stations.map { st =>
      val name = (st \ "name").as[String]
      val shortName = (st \ "short_name").as[String]
      val stationId = (st \ "station_id").as[String]
      val capacity = (st \ "capacity").as[Int]
      val lat = (st \ "lat").as[Double]
      val lon = (st \ "lon").as[Double]
      Station(name, shortName, stationId, capacity, lat, lon)
    }
  }

  logger.info(s"Number of stations: ${stations.size}")

  val geo = SimpleGeoUtils()
  stations.foreach(println)

  private val randomStations = Random.shuffle(stations).take((stations.size * 0.2).toInt)

  val zoneArray: Array[ParkingZone[TAZ]] = randomStations.zipWithIndex.map {
    case (station, idx) =>
      val stationCoord = geo.wgs2Utm(new Coord(station.lon, station.lat))
      val taz = tazMap.getTAZ(stationCoord)
      val capacity = if (station.capacity <= 0) 10 else station.capacity
      new ParkingZone[TAZ](
        idx,
        taz.tazId,
        ParkingType.Public,
        capacity,
        capacity,
        None,
        None,
        ChargingPointType("NoCharger"),
        Some(PricingModel.FlatFee(0))
      )
  }.toArray

  val generatedTree = TazToLinkLevelParkingApp.toSearchTree(zoneArray)

  ParkingZoneFileUtils.writeParkingZoneFile(generatedTree, zoneArray, argsMap("out"))

  logger.info("Written {} parking zones to {}", zoneArray.length, argsMap("out"))

  case class Station(name: String, shortName: String, stationId: String, capacity: Int, lat: Double, lon: Double)
}
