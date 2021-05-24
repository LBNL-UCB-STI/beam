package beam.router

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.{Location, RoutingRequest}
import beam.router.Modes.BeamMode.CAR
import beam.router.graphhopper.{CarGraphHopperWrapper, GraphHopperWrapper}
import beam.router.r5.{R5Parameters, R5Wrapper}
import beam.sim.BeamHelper
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.utils.DateUtils
import beam.utils.csv.{CsvWriter, GenericCsvReader}
import com.conveyal.osmlib.OSM
import org.matsim.api.core.v01.Id

import java.io.Closeable
import java.nio.file.Paths

case class InputParameters(
  departureTime: Int,
  configPath: String,
  linkstatsFilename: String,
  router: String,
  csvPath: String,
  csvOutPath: String
)
case class CsvInputRow(id: Int, originUTM: Location, destinationUTM: Location)
case class CsvOutputRow(id: Int, originUTM: Location, destinationUTM: Location, travelTime: Int, distance: Double)

class TravelTimeAndDistanceCalculatorApp(router: Router, parameters: InputParameters) extends BeamHelper {

  val geoUtils = new GeoUtils {
    override def localCRS: String = "epsg:32631"
  }

  def processRow(row: CsvInputRow): CsvOutputRow = {
    val streetVehicles: IndexedSeq[StreetVehicle] = Vector(
      StreetVehicle(
        Id.createVehicleId("1"),
        Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
        new SpaceTime(row.originUTM, time = parameters.departureTime),
        CAR,
        asDriver = true,
        needsToCalculateCost = true
      )
    )

    val request =
      RoutingRequest(
        row.originUTM,
        row.destinationUTM,
        parameters.departureTime,
        withTransit = false,
        None,
        streetVehicles
      )
    val response = router.calcRoute(request)

    if (response.itineraries.isEmpty) {
      logger.error("No itineraries found")
      throw new RuntimeException("No itineraries found")
    }

    CsvOutputRow(
      row.id,
      row.originUTM,
      row.destinationUTM,
      response.itineraries.head.totalTravelTimeInSecs,
      response.itineraries.head.legs.lastOption.map(_.beamLeg.travelPath.distanceInM).getOrElse(0)
    )
  }

  def toCsvRow(rec: java.util.Map[String, String]): CsvInputRow =
    CsvInputRow(
      rec.get("id").toInt,
      new Location(rec.get("origin_x").toDouble, rec.get("origin_y").toDouble),
      new Location(rec.get("destination_x").toDouble, rec.get("destination_y").toDouble)
    )

  def readCsv(csvPath: String): Vector[CsvInputRow] = {
    val (iter: Iterator[CsvInputRow], toClose: Closeable) =
      GenericCsvReader.readAs[CsvInputRow](csvPath, toCsvRow, _ => true)
    try {
      iter.toVector
    } finally {
      toClose.close()
    }
  }

  def processCsv(): Vector[CsvOutputRow] = readCsv(parameters.csvPath).map(processRow)

  def writeCsv(results: Vector[CsvOutputRow]): Unit = {
    val writer = CsvWriter(parameters.csvOutPath)
    try {
      results.foreach(
        row =>
          writer.writeRow(
            Seq(
              row.id,
              row.originUTM.getX,
              row.originUTM.getY,
              row.destinationUTM.getX,
              row.destinationUTM.getY,
              row.travelTime,
              row.distance
            )
        )
      )
    } finally {
      writer.close()
    }
  }

}

object TravelTimeAndDistanceCalculatorApp extends App with BeamHelper {
  def graphHopperDir: String = Paths.get(workerParams.beamConfig.beam.inputDirectory, "graphhopper").toString

  def id2Link: Map[Int, (Location, Location)] =
    workerParams.networkHelper.allLinks
      .map(x => x.getId.toString.toInt -> (x.getFromNode.getCoord -> x.getToNode.getCoord))
      .toMap

  private def createCarGraphHopper(): CarGraphHopperWrapper = {
    val wayId2TravelTime =
      workerParams.networkHelper.allLinks.toSeq
        .map(
          l =>
            l.getId.toString.toLong ->
            travelTime.getLinkTravelTime(l, parameters.departureTime.toDouble, null, null)
        )
        .toMap

    GraphHopperWrapper.createCarGraphDirectoryFromR5(
      "quasiDynamicGH",
      workerParams.transportNetwork,
      new OSM(workerParams.beamConfig.beam.routing.r5.osmMapdbFile),
      graphHopperDir,
      wayId2TravelTime
    )

    new CarGraphHopperWrapper(
      carRouter = "quasiDynamicGH",
      graphDir = graphHopperDir,
      geo = workerParams.geo,
      vehicleTypes = workerParams.vehicleTypes,
      fuelTypePrices = workerParams.fuelTypePrices,
      wayId2TravelTime = wayId2TravelTime,
      id2Link = id2Link
    )
  }

  def parseArgs(args: Array[String]) = {
    args
      .sliding(2, 2)
      .toList
      .collect {
        case Array("--departure-time", filePath: String) => ("departure-time", filePath)
        case Array("--config-path", filePath: String)    => ("config-path", filePath)
        case Array("--linkstats", filePath: String)      => ("linkstats", filePath)
        case Array("--router", filePath: String)         => ("router", filePath)
        case Array("--csv-path", filePath: String)       => ("csv-path", filePath)
        case Array("--out", filePath: String)            => ("out", filePath)
        case arg @ _ =>
          throw new IllegalArgumentException(arg.mkString(" "))
      }
      .toMap
  }

  val argsMap = parseArgs(args)

  if (argsMap.size != 6) {
    println("""
      |Usage: 
      | --departure-time 0
      | --config-path test/input/beamville/beam.conf
      | --linkstats test/input/beamville/linkstats.csv.gz
      | --router R5|GH
      | --csv-path test/input/beamville/input.csv
      | --out test/input/beamville/output.csv
    """.stripMargin)
    System.exit(1)
  }

  logger.info("args = {}", argsMap)

  val parameters: InputParameters = InputParameters(
    departureTime = argsMap("departure-time").toInt,
    configPath = argsMap("config-path"),
    linkstatsFilename = argsMap("linkstats"),
    router = argsMap("router").toUpperCase,
    csvPath = argsMap("csv-path"),
    csvOutPath = argsMap("out")
  )
  val manualArgs = Array[String]("--config", parameters.configPath)
  val (_, cfg) = prepareConfig(manualArgs, isConfigArgRequired = true)

  val workerParams: R5Parameters = R5Parameters.fromConfig(cfg)
  val beamConfig = BeamConfig(cfg)
  val timeBinSizeInSeconds = beamConfig.beam.agentsim.timeBinSize
  val maxHour = DateUtils.getMaxHour(beamConfig)
  val travelTime = new LinkTravelTimeContainer(parameters.linkstatsFilename, timeBinSizeInSeconds, maxHour)
  val travelTimeNoiseFraction = beamConfig.beam.routing.r5.travelTimeNoiseFraction

  val router =
    if (parameters.router == "R5") {
      logger.info("Using R5 router")
      new R5Wrapper(workerParams, travelTime, travelTimeNoiseFraction)
    } else {
      logger.info("Using GraphHopper router")
      createCarGraphHopper()
    }

  val app = new TravelTimeAndDistanceCalculatorApp(router, parameters)
  val results = app.processCsv()
  app.writeCsv(results)
}
