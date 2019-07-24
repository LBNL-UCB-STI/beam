package beam.sim

import java.io.{File, FileNotFoundException}
import java.nio.file.{Files, Paths}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import akka.actor.ActorRef
import beam.router.BeamRouter.{UpdateTravelTimeLocal, UpdateTravelTimeRemote}
import beam.router.LinkTravelTimeContainer
import beam.sim.config.{BeamConfig, BeamExecutionConfig}
import beam.utils.FileUtils.downloadFile
import beam.utils.TravelTimeCalculatorHelper
import beam.utils.UnzipUtility._
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.io.FileUtils.getTempDirectoryPath
import org.apache.commons.io.FilenameUtils.{getBaseName, getExtension, getName}
import org.matsim.api.core.v01.Scenario
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup
import org.matsim.core.router.util.TravelTime
import scala.compat.java8.StreamConverters._
import scala.util.Try

class BeamWarmStart private (beamConfig: BeamConfig) extends LazyLogging {
  val isWarmMode: Boolean = beamConfig.beam.warmStart.enabled
  if (!isWarmMode) {
    throw new IllegalStateException("BeamWarmStart cannot be initialized since warmstart is disabled")
  }

  private val srcPath = beamConfig.beam.warmStart.path

  private lazy val linkStatsFilePath: Option[String] = {
    val linkStatsFileName = "linkstats.csv.gz"
    getWarmStartFilePath(linkStatsFileName, rootFirst = false) match {
      case result @ Some(filePath: String) if Files.isRegularFile(Paths.get(filePath)) =>
        logger.info("Read travel times from {}.", filePath)
        result
      case _ =>
        logger.warn("Travel times failed to warm start, stats not found for file ( {} )", linkStatsFileName)
        None
    }
  }

  private def compressedLocation(description: String, fileName: String): String = {
    getWarmStartFilePath(fileName) match {
      case Some(compressedFileFullPath) =>
        if (Files.isRegularFile(Paths.get(compressedFileFullPath))) {
          extractFileFromZip(parentRunPath, compressedFileFullPath, fileName)
        } else {
          throwErrorFileNotFound(description, compressedFileFullPath)
        }
      case None =>
        throwErrorFileNotFound(description, srcPath)
    }
  }

  private def notCompressedLocation(description: String, fileName: String, rootFirst: Boolean): String = {
    getWarmStartFilePath(fileName, rootFirst) match {
      case Some(fileFullPath) if Files.isRegularFile(Paths.get(fileFullPath)) =>
        logger.info(s"**** warmStartFile method fileName:[$fileName]. notCompressedFile:[$fileFullPath]")
        fileFullPath
      case _ =>
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

  private lazy val parentRunPath: String = {
    if (isZipArchive(srcPath)) {
      var archivePath = srcPath
      if (isOutputBucketUrl(srcPath)) {
        archivePath = Paths.get(getTempDirectoryPath, getName(srcPath)).toString
        downloadFile(srcPath, archivePath)
      }
      val runPath = Paths.get(getTempDirectoryPath, getBaseName(srcPath)).toString
      unzip(archivePath, runPath, false)

      runPath
    } else {
      srcPath
    }
  }

  private def isOutputBucketUrl(source: String): Boolean = {
    assert(source != null)
    source.startsWith("https") && source.contains("amazonaws.com")
  }

  private def isZipArchive(source: String): Boolean = {
    assert(source != null)
    "zip".equalsIgnoreCase(getExtension(source))
  }

}

object BeamWarmStart extends LazyLogging {

  private val reference = new AtomicReference[BeamWarmStart]()

  def apply(beamConfig: BeamConfig): BeamWarmStart = {
    reference.updateAndGet { current =>
      if (current == null) {
        new BeamWarmStart(beamConfig)
      } else {
        current
      }
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

  def warmStartTravelTime(
    beamConfig: BeamConfig,
    calculator: TravelTimeCalculatorConfigGroup,
    beamRouter: ActorRef,
    scenario: Scenario
  ): Unit = {
    if (beamConfig.beam.warmStart.enabled) {
      val maxHour = maxHoursFromCalculator(calculator)
      readTravelTime(beamConfig, maxHour).foreach { travelTime =>
        beamRouter ! UpdateTravelTimeLocal(travelTime)
        BeamWarmStart.updateRemoteRouter(scenario, travelTime, maxHour, beamRouter)
        logger.info("Travel times successfully warm started from")
      }
    }
  }

  def readTravelTime(beamConfig: BeamConfig, maxHour: Int): Option[TravelTime] = {
    this(beamConfig).linkStatsFilePath match {
      case Some(statsFile) =>
        val binSize = beamConfig.beam.agentsim.timeBinSize
        Some(new LinkTravelTimeContainer(statsFile, binSize, maxHour))
      case None =>
        None
    }
  }

  def maxHoursFromCalculator(calculator: TravelTimeCalculatorConfigGroup): Int = {
    TimeUnit.SECONDS.toHours(calculator.getMaxTime).toInt
  }

  def updateExecutionConfig(beamExecutionConfig: BeamExecutionConfig): BeamExecutionConfig = {
    logger.error("Warmstart updateExecutionConfig")
    val beamConfig: BeamConfig = beamExecutionConfig.beamConfig

    if (beamConfig.beam.warmStart.enabled) {
      val instance = BeamWarmStart(beamConfig)
      val matsimConfig = beamExecutionConfig.matsimConfig

      if (beamConfig.beam.outputs.writeSkimsInterval == 0 && beamConfig.beam.warmStart.enabled) {
        logger.warn(
          "Beam skims are not being written out - skims will be missing for warm starting from the output of this run!"
        )
      }
      val configAgents = beamConfig.beam.agentsim.agents
      val scenarioConfig = beamConfig.beam.exchange.scenario

      val populationAttributesXml = instance.compressedLocation("Person attributes", "outputPersonAttributes.xml.gz")
      matsimConfig.plans().setInputPersonAttributeFile(populationAttributesXml)
      val populationAttributesCsv =
        instance.notCompressedLocation("Person attributes", "population.csv", rootFirst = true)

      val plansXml = instance.compressedLocation("Plans", "plans.xml.gz")
      matsimConfig.plans().setInputFile(plansXml)
      val plansCsv = instance.notCompressedLocation("Plans", "plans.csv", rootFirst = false)

      val houseHoldsCsv = instance.notCompressedLocation("Households", "households.csv", rootFirst = true)

      val vehiclesCsv = instance.notCompressedLocation("Households", "vehicles.csv", rootFirst = true)

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

      val newWarmStart = {
        val newSkimsFilePath = Try(instance.compressedLocation("Skims file", "skims.csv.gz")).getOrElse("")
        val newSkimPlusFilePath = Try(instance.compressedLocation("Skim plus", "skimsPlus.csv.gz")).getOrElse("")
        val newRouteHistoryFilePath =
          Try(instance.compressedLocation("Route history", "routeHistory.csv.gz")).getOrElse("")

        beamConfig.beam.warmStart.copy(
          skimsFilePath = newSkimsFilePath,
          skimsPlusFilePath = newSkimPlusFilePath,
          routeHistoryFilePath = newRouteHistoryFilePath
        )
      }

      val newBeam = beamConfig.beam.copy(agentsim = newAgentSim, exchange = newExchange, warmStart = newWarmStart)
      val newBeamConfig = beamConfig.copy(beam = newBeam)

      beamExecutionConfig.copy(beamConfig = newBeamConfig)
    } else {
      beamExecutionConfig
    }
  }
}
