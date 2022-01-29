package beam.router

import beam.agentsim.agents.vehicles.VehicleCategory
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.{Location, RoutingRequest}
import beam.router.Modes.BeamMode.CAR
import beam.router.graphhopper.{CarGraphHopperWrapper, GraphHopperWrapper}
import beam.router.r5.{R5Parameters, R5Wrapper}
import beam.sim.BeamHelper
import beam.sim.config.BeamConfig
import beam.utils.DateUtils
import beam.utils.csv.{CsvWriter, GenericCsvReader}
import com.conveyal.osmlib.OSM
import org.matsim.api.core.v01.Id
import scopt.OParser

import java.io.{Closeable, File}
import java.nio.file.{Path, Paths}

case class InputParameters(
  departureTime: Int = 0,
  configPath: Path = null,
  linkstatsPath: Path = null,
  router: String = "",
  input: Path = null,
  output: Path = null
)
case class CsvInputRow(id: Int, originUTM: Location, destinationUTM: Location)
case class CsvOutputRow(id: Int, originUTM: Location, destinationUTM: Location, travelTime: Int, distance: Double)

/*
Example of parameters usage:
 --departureTime 0
 --configPath test/input/beamville/beam.conf
 --linkstatsPath test/input/beamville/linkstats.csv.gz
 --router R5|GH
 --input test/input/beamville/input.csv
 --output test/input/beamville/output.csv
 */
object TravelTimeAndDistanceCalculatorApp extends App with BeamHelper {

  private val parser = {
    val builder = OParser.builder[InputParameters]
    import builder._

    def fileValidator(file: File): Either[String, Unit] =
      if (file.isFile) success
      else failure(s"$file does not exist")

    OParser.sequence(
      programName("travel-time-and-distance-calculator"),
      opt[Int]("departureTime").required().text("0"),
      opt[File]("configPath")
        .required()
        .validate(fileValidator)
        .action((x, c) => c.copy(configPath = x.toPath))
        .text("Beam config path"),
      opt[File]("linkstatsPath")
        .required()
        .validate(fileValidator)
        .action((x, c) => c.copy(linkstatsPath = x.toPath))
        .text("linkstats file path in csv.gz format"),
      opt[String]("router").action((v, c) => c.copy(router = v.toUpperCase)).text("R5/GH"),
      opt[File]("input")
        .required()
        .validate(fileValidator)
        .action((x, c) => c.copy(input = x.toPath))
        .text("input csv file path"),
      opt[File]("output").required().action((x, c) => c.copy(output = x.toPath)).text("output csv file path")
    )
  }

  OParser.parse(parser, args, InputParameters()) match {
    case Some(params) =>
      logger.info("params = {}", params)
      val app = new TravelTimeAndDistanceCalculatorApp(params)
      val results = app.processCsv()
      app.writeCsv(results)
    case _ =>
      println("Could not process parameters")
  }

}

class TravelTimeAndDistanceCalculatorApp(parameters: InputParameters) extends BeamHelper {
  val manualArgs = Array[String]("--config", parameters.configPath.toString)
  val (_, cfg) = prepareConfig(manualArgs, isConfigArgRequired = true)
  val beamConfig = BeamConfig(cfg)
  val timeBinSizeInSeconds = beamConfig.beam.agentsim.timeBinSize
  val maxHour = DateUtils.getMaxHour(beamConfig)
  val workerParams: R5Parameters = R5Parameters.fromConfig(cfg)
  val travelTimeNoiseFraction = beamConfig.beam.routing.r5.travelTimeNoiseFraction
  val travelTime = new LinkTravelTimeContainer(parameters.linkstatsPath.toString, timeBinSizeInSeconds, maxHour)

  // Get the first CAR vehicle type or if not available the first vehicle type
  val vehicleTypeId =
    workerParams.vehicleTypes.find(_._2.vehicleCategory == VehicleCategory.Car).map(_._1).getOrElse {
      workerParams.vehicleTypes.headOption.map(_._1).getOrElse {
        throw new RuntimeException("Vehicle type not set")
      }
    }

  val router =
    if (parameters.router == "R5") {
      logger.info("Using R5 router")
      new R5Wrapper(workerParams, travelTime, travelTimeNoiseFraction)
    } else {
      logger.info("Using GraphHopper router")
      createCarGraphHopper()
    }

  def graphHopperDir: String = Paths.get(workerParams.beamConfig.beam.inputDirectory, "graphhopper").toString

  def id2Link: Map[Int, (Location, Location)] =
    workerParams.networkHelper.allLinks
      .map(x => x.getId.toString.toInt -> (x.getFromNode.getCoord -> x.getToNode.getCoord))
      .toMap

  private def createCarGraphHopper(): CarGraphHopperWrapper = {
    val wayId2TravelTime =
      workerParams.networkHelper.allLinks.toSeq
        .map(l =>
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
      id2Link = id2Link,
      useAlternativeRoutes = workerParams.beamConfig.beam.routing.gh.useAlternativeRoutes
    )
  }

  private def processRow(row: CsvInputRow): CsvOutputRow = {
    val streetVehicles: IndexedSeq[StreetVehicle] = Vector(
      StreetVehicle(
        Id.createVehicleId("1"),
        vehicleTypeId,
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
        streetVehicles,
        triggerId = 0L
      )
    val response = router.calcRoute(request)

    if (response.itineraries.isEmpty) {
      logger.error("No itineraries found")
      throw new RuntimeException("No itineraries found")
    }

    val travelPath = response.itineraries.head.legs.lastOption.map(_.beamLeg.travelPath)

    CsvOutputRow(
      row.id,
      row.originUTM,
      row.destinationUTM,
      travelPath.map(_.linkTravelTime.sum.toInt).getOrElse(0),
      travelPath.map(_.distanceInM).getOrElse(0)
    )
  }

  def toCsvRow(rec: java.util.Map[String, String]): CsvInputRow =
    CsvInputRow(
      rec.get("id").toInt,
      new Location(rec.get("origin_x").toDouble, rec.get("origin_y").toDouble),
      new Location(rec.get("destination_x").toDouble, rec.get("destination_y").toDouble)
    )

  private def readCsv(csvPath: String): Vector[CsvInputRow] = {
    val (iter: Iterator[CsvInputRow], toClose: Closeable) =
      GenericCsvReader.readAs[CsvInputRow](csvPath, toCsvRow, _ => true)
    try {
      iter.toVector
    } finally {
      toClose.close()
    }
  }

  def processCsv(): Vector[CsvOutputRow] = readCsv(parameters.input.toString).map(processRow)

  def writeCsv(results: Vector[CsvOutputRow]): Unit = {
    val writer = CsvWriter(
      parameters.output.toString,
      Seq("id", "origin_x", "origin_y", "destination_x", "destination_y", "traveltime", "distance"): _*
    )
    try {
      results.foreach(row =>
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
