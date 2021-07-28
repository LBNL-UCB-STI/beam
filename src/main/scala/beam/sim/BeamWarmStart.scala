package beam.sim

import java.io.{File, FileNotFoundException}
import java.nio.file.{Files, Paths}
import scala.collection.concurrent.TrieMap
import scala.compat.java8.StreamConverters._
import scala.util.Try
import scala.util.control.NonFatal
import akka.actor.ActorRef
import beam.router.BeamRouter.{UpdateTravelTimeLocal, UpdateTravelTimeRemote}
import beam.router.LinkTravelTimeContainer
import beam.router.skim.Skims
import beam.sim.config.{BeamConfig, BeamExecutionConfig}
import beam.sim.config.BeamConfig.Beam
import beam.sim.BeamWarmStart.WarmStartConfigProperties
import beam.sim.config.BeamConfig.Beam.WarmStart.SkimsFilePaths$Elm
import beam.utils.{DateUtils, FileUtils, TravelTimeCalculatorHelper}
import beam.utils.UnzipUtility._
import beam.utils.scenario.LastRunOutputSource
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.io.FilenameUtils.getName
import org.matsim.api.core.v01.Scenario
import org.matsim.core.config.Config
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.router.util.TravelTime

class BeamWarmStart private (val warmConfig: WarmStartConfigProperties) extends LazyLogging {

  private val srcPath: String = warmConfig.warmStartPath

  def readTravelTime: Option[TravelTime] = {
    readTravelTime(getWarmStartFilePath("linkstats.csv.gz", rootFirst = false), srcPath)
  }

  def readTravelTime(linkStatsPath: Option[String], searchPath: String): Option[TravelTime] = {
    linkStatsPath match {
      case Some(statsPath) =>
        if (Files.isRegularFile(Paths.get(statsPath))) {
          val travelTime = getTravelTime(statsPath)
          logger.info("Read travel times from {}.", statsPath)
          Some(travelTime)
        } else {
          logger.warn("Travel times failed to warm start, stats not found at path ( {} )", statsPath)
          None
        }
      case _ =>
        logger.warn("Travel times failed to warm start, stats not found at path ( {} )", searchPath)
        None
    }
  }

  private def compressedLocation(description: String, fileName: String, rootFirst: Boolean = true): String = {
    getWarmStartFilePath(fileName, rootFirst) match {
      case Some(compressedFileFullPath) =>
        logger.info(
          s"compressedLocation: description: $description, fileName: $fileName, compressedFileFullPath: $compressedFileFullPath"
        )
        if (Files.isRegularFile(Paths.get(compressedFileFullPath))) {
          extractFileFromZip(parentRunPath, compressedFileFullPath, fileName)
        } else {
          logger.warn(
            s"not a regular file: description: $description, fileName: $fileName, compressedFileFullPath: $compressedFileFullPath"
          )
          throwErrorFileNotFound(description, compressedFileFullPath)
        }
      case None =>
        logger.warn(
          s"not found: description: $description, fileName: $fileName, rootFirst: $rootFirst"
        )
        throwErrorFileNotFound(description, srcPath)
    }
  }

  @throws(classOf[FileNotFoundException])
  private def throwErrorFileNotFound(fileDesc: String, path: String): String = {
    throw new FileNotFoundException(s"Warmstart configuration is invalid. [$fileDesc] not found at path [$path]")
  }

  private def extractFileFromZip(runPath: String, zipFileFullPath: String, fileName: String): String = {
    val newFileName = fileName.dropRight(".gz".length)
    val plansPath = Paths.get(runPath, s"warmstart_$newFileName").toString
    unGunzipFile(zipFileFullPath, plansPath, false)
    plansPath
  }

  def getWarmStartFilePath(warmStartFile: String, rootFirst: Boolean = true): Option[String] = {
    lazy val itrFile: Option[String] = findIterationWarmStartFile(warmStartFile, parentRunPath)
    lazy val rootFile: Option[String] = findRootWarmStartFile(warmStartFile)

    if (rootFirst) {
      rootFile.fold(itrFile)(Some(_))
    } else {
      itrFile.fold(rootFile)(Some(_))
    }
  }

  private def findRootWarmStartFile(warmStartFile: String): Option[String] = {
    val search = findFileInDir(warmStartFile, parentRunPath)

    if (search.nonEmpty) {
      search

    } else {
      val iters = getITERSPath(parentRunPath)

      if (iters.nonEmpty) {
        findFileInDir(warmStartFile, Paths.get(iters.head).getParent.toString)

      } else {
        Files.walk(Paths.get(parentRunPath)).toScala[Stream].map(_.toString).find(_.endsWith(warmStartFile))
      }
    }
  }

  private def findIterationWarmStartFile(itFile: String, runPath: String): Option[String] = {
    getITERSPath(runPath) match {
      case Some(iterBase) =>
        findIterationContainsFile(itFile, iterBase) match {
          case Some(warmIteration) =>
            Some(
              Paths.get(iterBase, s"it.$warmIteration", s"$warmIteration.$itFile").toString
            )
          case None =>
            None
        }
      case None =>
        None
    }
  }

  private def findIterationContainsFile(itFile: String, iterBase: String) = {
    new File(iterBase)
      .list()
      .filter(_.startsWith("it."))
      .map(_.split('.')(1).toInt)
      .sorted
      .reverse
      .find(isFilePresentInIteration(itFile, iterBase, _))
  }

  private def isFilePresentInIteration(itFile: String, itrBaseDir: String, itr: Int): Boolean = {
    val linkStats = Paths.get(itrBaseDir, s"it.$itr", s"$itr.$itFile")
    Files.isRegularFile(linkStats)
  }

  private def getITERSPath(runPath: String): Option[String] = {
    Files
      .walk(Paths.get(runPath))
      .toScala[Stream]
      .map(_.toString)
      .find(p => "ITERS".equals(getName(p)))
  }

  private def findFileInDir(file: String, dir: String): Option[String] = {
    new File(dir).listFiles().map(_.getAbsolutePath).find(_.endsWith(file))
  }

  private lazy val parentRunPath: String = FileUtils.downloadAndUnpackIfNeeded(srcPath)

  private def getTravelTime(statsFile: String): TravelTime = {
    val binSize: Int = warmConfig.agentsimTimeBinSize
    new LinkTravelTimeContainer(statsFile, binSize, warmConfig.maxHour)
  }

}

object BeamWarmStart extends LazyLogging {
  private val instances = TrieMap.empty[WarmStartConfigProperties, BeamWarmStart]

  private[sim] case class WarmStartConfigProperties(
    warmStartPath: String,
    agentsimTimeBinSize: Int,
    maxHour: Int
  )

  private def buildWarmConfig(beamConfig: BeamConfig, maxHour: Int): WarmStartConfigProperties = {
    WarmStartConfigProperties(
      warmStartPath = beamConfig.beam.warmStart.path,
      agentsimTimeBinSize = beamConfig.beam.agentsim.timeBinSize,
      maxHour = maxHour
    )
  }

  val fileNameSubstringToDetectIfReadSkimsInParallelMode = "_part"

  def apply(
    beamConfig: BeamConfig,
    maxHour: Int
  ): BeamWarmStart = {
    val warmConfig = buildWarmConfig(beamConfig, maxHour)
    instances.getOrElseUpdate(
      warmConfig, {
        val msg = s"Adding a new instance of WarmStart... configuration: [$warmConfig]"
        if (instances.isEmpty) {
          logger.info(msg)
        } else {
          logger.warn(msg)
        }
        new BeamWarmStart(warmConfig)
      }
    )
  }

  def warmStartTravelTime(
    beamConfig: BeamConfig,
    beamRouter: ActorRef,
    scenario: Scenario
  ): Option[TravelTime] = {
    if (BeamWarmStart.isLinkStatsEnabled(beamConfig.beam.warmStart)) {
      val maxHour = DateUtils.getMaxHour(beamConfig)
      val warm = BeamWarmStart(beamConfig, maxHour)
      val travelTime =
        if (BeamWarmStart.isLinkStatsFromLastRun(beamConfig.beam.warmStart)) {
          val linkStatsPath = LastRunOutputSource
            .findLastRunLinkStats(
              Paths.get(beamConfig.beam.input.lastBaseOutputDir),
              beamConfig.beam.input.simulationPrefix
            )
            .map(_.toString)
          warm.readTravelTime(linkStatsPath, beamConfig.beam.input.lastBaseOutputDir)
        } else warm.readTravelTime
      travelTime.foreach { travelTime =>
        beamRouter ! UpdateTravelTimeLocal(travelTime)
        BeamWarmStart.updateRemoteRouter(scenario, travelTime, maxHour, beamRouter)
        logger.info("Travel times successfully warm started from")
      }
      travelTime
    } else {
      None
    }
  }

  def updateRemoteRouter(scenario: Scenario, travelTime: TravelTime, maxHour: Int, beamRouter: ActorRef): Unit = {
    val map = TravelTimeCalculatorHelper.GetLinkIdToTravelTimeArray(
      scenario.getNetwork.getLinks.values(),
      travelTime,
      maxHour
    )
    beamRouter ! UpdateTravelTimeRemote(map)
  }

  def updateExecutionConfig(beamExecutionConfig: BeamExecutionConfig): BeamExecutionConfig = {
    val beamConfig = beamExecutionConfig.beamConfig

    if (BeamWarmStart.isFullWarmStart(beamConfig.beam.warmStart)) {
      val matsimConfig = beamExecutionConfig.matsimConfig

      if (beamConfig.beam.router.skim.writeSkimsInterval == 0) {
        logger.warn(
          "Beam skims are not being written out - skims will be missing for warm starting from the output of this run!"
        )
      }

      val instance = BeamWarmStart(beamConfig, DateUtils.getMaxHour(beamConfig))

      val newWarmStartConfig: Beam.WarmStart = {
        val skimCfg = beamConfig.beam.router.skim

        val newSkimsFilePath: IndexedSeq[SkimsFilePaths$Elm] =
          Skims.skimAggregatedFileNames(skimCfg).map { case (skimType, fileName) =>
            val filePath = Try(instance.compressedLocation("Skims file", fileName))
              .getOrElse(instance.parentRunPath)
            SkimsFilePaths$Elm(skimType.toString, filePath)
          }

        beamConfig.beam.warmStart.copy(
          skimsFilePaths = Some(newSkimsFilePath.toList)
        )
      }

      val newBeamConfig = getAgentSimExchangeTuple(instance, beamConfig, matsimConfig) match {
        case Some((agentSim, exchange)) =>
          val newBeam = beamConfig.beam.copy(agentsim = agentSim, exchange = exchange, warmStart = newWarmStartConfig)
          beamConfig.copy(beam = newBeam)
        case None =>
          val newBeam = beamConfig.beam.copy(warmStart = newWarmStartConfig)
          beamConfig.copy(beam = newBeam)
      }

      beamExecutionConfig.copy(beamConfig = newBeamConfig)
    } else {
      beamExecutionConfig
    }
  }

  def getAgentSimExchangeTuple(
    instance: BeamWarmStart,
    beamConfig: BeamConfig,
    matsimConfig: Config
  ): Option[(Beam.Agentsim, Beam.Exchange)] = {
    val configAgents = beamConfig.beam.agentsim.agents
    try {
      val populationAttributesXml = instance.compressedLocation("Person attributes", "output_personAttributes.xml.gz")
      matsimConfig.plans().setInputPersonAttributeFile(populationAttributesXml)
      val populationAttributesCsv = instance.compressedLocation("Population", "population.csv.gz")

      // We need to get the plans from the iteration folder, not root!
      val plansXml = instance.compressedLocation("Plans.xml", "plans.xml.gz", rootFirst = false)
      matsimConfig.plans().setInputFile(plansXml)
      // We need to get the plans from the iteration folder, not root!
      val plansCsv = instance.compressedLocation("Plans.csv", "plans.csv.gz", rootFirst = false)

      val houseHoldsCsv = instance.compressedLocation("Households", "households.csv.gz")

      val vehiclesCsv = instance.compressedLocation("Vehicles", "vehicles.csv.gz")

      val rideHailFleetCsv = instance.compressedLocation("Ride-hail fleet state", "rideHailFleet.csv.gz")
      val newRideHailInit = {
        val updatedInitCfg = configAgents.rideHail.initialization.copy(filePath = rideHailFleetCsv, initType = "FILE")
        configAgents.rideHail.copy(initialization = updatedInitCfg)
      }

      val newConfigAgents = {
        val newPlans = {
          configAgents.plans
            .copy(inputPersonAttributesFilePath = populationAttributesCsv, inputPlansFilePath = plansCsv)
        }

        val newHouseHolds = configAgents.households.copy(inputFilePath = houseHoldsCsv)

        val newVehicles = configAgents.vehicles.copy(vehiclesFilePath = vehiclesCsv)

        configAgents.copy(
          plans = newPlans,
          households = newHouseHolds,
          vehicles = newVehicles,
          rideHail = newRideHailInit
        )
      }

      val newAgentSim = beamConfig.beam.agentsim.copy(agents = newConfigAgents)

      val newExchange = {
        val newExchangeScenario = beamConfig.beam.exchange.scenario.copy(source = "Beam", fileFormat = "csv")
        beamConfig.beam.exchange.copy(scenario = newExchangeScenario)
      }

      Some(newAgentSim, newExchange)
    } catch {
      case NonFatal(exception: Exception) =>
        logger.info(s"Parts of population/housholds/vehicles could not be loaded ${exception.getMessage}")
        None
    }
  }

  def prepareWarmStartArchive(
    beamConfig: BeamConfig,
    controllerIO: OutputDirectoryHierarchy,
    firstIteration: Int,
    lastIteration: Int
  ): Option[String] = {
    val actualLastIteration = (firstIteration to lastIteration).reverse
      .map(i => i -> org.apache.commons.io.FileUtils.getFile(new File(controllerIO.getIterationPath(i))))
      .find { case (_, file) => file.isDirectory }
    actualLastIteration match {
      case Some((iteration, dir)) =>
        logger.info(s"Using $dir to prepare warmstart archive")
        val skimCfg = beamConfig.beam.router.skim
        val skimFileNames = Skims
          .skimAggregatedFileNames(skimCfg)
          .map { case (_, name) => name }
          .map(name => name -> controllerIO.getIterationFilename(iteration, name))
        val skimFiles = skimFileNames.map { case (name, pathStr) =>
          s"ITERS/it.$iteration/$iteration.$name" -> Paths.get(pathStr)
        }
        val rootFiles = IndexedSeq(
          "output_personAttributes.xml.gz",
          "population.csv.gz",
          "households.csv.gz",
          "vehicles.csv.gz"
        ).map(name => name -> Paths.get(controllerIO.getOutputFilename(name)))
        val iterationFiles = IndexedSeq(
          "linkstats.csv.gz",
          "plans.csv.gz",
          "plans.xml.gz",
          "rideHailFleet.csv.gz"
        ).map(name =>
          s"ITERS/it.$iteration/$iteration.$name" -> Paths.get(controllerIO.getIterationFilename(iteration, name))
        )
        val files = rootFiles ++ skimFiles ++ iterationFiles
        Some(FileUtils.zipFiles(controllerIO.getOutputFilename("warmstart_data.zip"), files))
      case None =>
        logger.warn("No iteration to prepare warmstart")
        None
    }
  }

  def isLinkStatsEnabled(warmStart: Beam.WarmStart): Boolean = warmStart.`type`.toLowerCase match {
    case "linkstatsonly"        => true
    case "linkstatsfromlastrun" => true
    case "full"                 => true
    case _                      => false
  }

  def isLinkStatsFromLastRun(warmStart: Beam.WarmStart): Boolean = warmStart.`type`.toLowerCase match {
    case "linkstatsfromlastrun" => true
    case _                      => false
  }

  def isFullWarmStart(warmStart: Beam.WarmStart): Boolean = warmStart.`type`.toLowerCase match {
    case "full" => true
    case _      => false
  }
}
