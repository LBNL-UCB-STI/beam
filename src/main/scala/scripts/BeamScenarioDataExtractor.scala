package scripts

import beam.sim.BeamHelper
import beam.sim.config.BeamExecutionConfig
import beam.utils.csv.writers.VehiclesCsvWriter
import beam.utils.scenario.VehicleInfo

import java.io.File
import java.nio.file.{Path, Paths}
import scala.jdk.CollectionConverters.collectionAsScalaIterableConverter

/**
  * This app can load beam scenario and save wanted data to file(s). Now only vehicles.csv supported
  * {{{
  * ./gradlew :execute -PmainClass=scripts.BeamScenarioDataExtractor \
  *  -PappArgs="['--config', 'test/input/beamville/beam.conf', '--output', 'vehicles.csv']"
  * }}}
  *
  * @author Dmitry Openkov
  */
object BeamScenarioDataExtractor extends App with BeamHelper {

  parseArgs(args) match {
    case Some(cliOptions) =>
      doJob(cliOptions.configPath, cliOptions.output)
    case None => System.exit(1)
  }

  private def doJob(configPath: Path, output: Path): Unit = {
    val manualArgs = Array[String]("--config", configPath.toString)
    val (_, config) = prepareConfig(manualArgs, isConfigArgRequired = true)
    val beamExecutionConfig: BeamExecutionConfig = setupBeamWithConfig(config)
    val (scenario, beamScenario, _) =
      buildBeamServicesAndScenario(beamExecutionConfig.beamConfig, beamExecutionConfig.matsimConfig)

    logger.info("Extracting vehicles to {}", output)

    val households = scenario.getHouseholds.getHouseholds.values().asScala
    val vehicleInfos = (
      for {
        household <- households
        vehicleId <- household.getVehicleIds.asScala
        vehicle = beamScenario.privateVehicles(vehicleId)
        soc = beamScenario.privateVehicleInitialSoc.get(vehicleId)
      } yield VehicleInfo(vehicleId.toString, vehicle.beamVehicleType.id.toString, soc, household.getId.toString)
    ).toIndexedSeq

    val probablySortedInfos =
      if (vehicleInfos.forall(_.vehicleId.forall(_.isDigit)))
        vehicleInfos.sortBy(vehicleInfo => vehicleInfo.vehicleId.toInt)
      else vehicleInfos

    VehiclesCsvWriter(Seq.empty).toCsvWithHeader(probablySortedInfos.iterator, output.toString)
    logger.info("Saved {} vehicles to {}", vehicleInfos.size, output.toAbsolutePath)

  }

  case class CliOptions(
    configPath: Path,
    output: Path
  )

  private def parseArgs(args: Array[String]): Option[CliOptions] = {
    import scopt.OParser
    val builder = OParser.builder[CliOptions]
    val parser1 = {
      import builder._
      OParser.sequence(
        programName("shp-to-csv-converter"),
        head("This program converts an shp file to a csv that contains centroids fo polygons with their ids"),
        opt[File]("config")
          .required()
          .validate(file => if (file.isFile) success else failure(s"Wrong config file: $file"))
          .action((x, c) => c.copy(configPath = x.toPath))
          .text("Beam config path"),
        opt[File]('o', "output")
          .required()
          .valueName("<file>")
          .action((x, c) => c.copy(output = x.toPath))
          .text("path where to save the adopted vehicles"),
        help("help")
      )
    }
    OParser.parse(parser1, args, CliOptions(Paths.get("."), Paths.get(".")))
  }
}
