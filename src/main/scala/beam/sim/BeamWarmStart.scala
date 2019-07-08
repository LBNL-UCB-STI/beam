package beam.sim

import java.io.{File, FileNotFoundException}
import java.nio.file.{Files, Paths}
import java.util.concurrent.TimeUnit

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

class BeamWarmStart private (beamConfig: BeamConfig, maxHour: Int) extends LazyLogging {
  val isWarmMode = beamConfig.beam.warmStart.enabled
  if (!isWarmMode) { // TODO: it will be inlined
    throw new IllegalStateException("BeamWarmStart cannot be initialized since warmstart is disabled")
  }

  val srcPath = beamConfig.beam.warmStart.path

  def readTravelTime: Option[TravelTime] = {
    getWarmStartFilePath("linkstats.csv.gz", rootFirst = false) match {
      case Some(statsPath) if isWarmMode =>
        if (Files.isRegularFile(Paths.get(statsPath))) {
          val travelTime = getTravelTime(statsPath)
          logger.info("Read travel times from {}.", statsPath)
          Some(travelTime)
        } else {
          logger.warn("Travel times failed to warm start, stats not found at path ( {} )", statsPath)
          None
        }
      case _ =>
        logger.warn("Travel times failed to warm start, stats not found at path ( {} )", srcPath)
        None
    }
  }

  private def compressedLocation(description: String, fileName: String): String = {
    getWarmStartFilePath(fileName) match {
      case Some(compressedFileFullPath) =>
        logger.info(s"**** warmStartFile method fileName:[$fileName]. compressedFile:[$compressedFileFullPath]")
        if (Files.isRegularFile(Paths.get(compressedFileFullPath))) {
          extractFileFromZip(parentRunPath, compressedFileFullPath, fileName)
        } else {
          throwErrorFileNotFound(description, compressedFileFullPath)
        }
      case None =>
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
    source.startsWith("https://s3.us-east-2.amazonaws.com/beam-outputs/")
  }

  private def isZipArchive(source: String): Boolean = {
    assert(source != null)
    "zip".equalsIgnoreCase(getExtension(source))
  }

  private def getTravelTime(statsFile: String): TravelTime = {
    val binSize = beamConfig.beam.agentsim.timeBinSize

    new LinkTravelTimeContainer(statsFile, binSize, maxHour)
  }

}

object BeamWarmStart extends LazyLogging {

  // @deprecated("Warmstart should not be instantiated. It should use config file", since = "2019-07-04")
  def apply(beamConfig: BeamConfig): BeamWarmStart = {
    val maxHour = TimeUnit.SECONDS.toHours(new TravelTimeCalculatorConfigGroup().getMaxTime).toInt
    new BeamWarmStart(beamConfig, maxHour)
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
      val maxHour = TimeUnit.SECONDS.toHours(calculator.getMaxTime).toInt
      val warm = new BeamWarmStart(beamConfig, maxHour)
      warm.readTravelTime.foreach { travelTime =>
        beamRouter ! UpdateTravelTimeLocal(travelTime)
        BeamWarmStart.updateRemoteRouter(scenario, travelTime, maxHour, beamRouter)
        logger.info("Travel times successfully warm started from")
      }
    }
  }

  def updateExecutionConfig(beamExecutionConfig: BeamExecutionConfig): BeamExecutionConfig = {
    val beamConfig = beamExecutionConfig.beamConfig

    if (beamConfig.beam.warmStart.enabled) {
      val matsimConfig = beamExecutionConfig.matsimConfig

      if (beamConfig.beam.outputs.writeSkimsInterval == 0 && beamConfig.beam.warmStart.enabled) {
        logger.warn(
          "Beam skims are not being written out - skims will be missing for warm starting from the output of this run!"
        )
      }
      val instance = {
        val maxHour = TimeUnit.SECONDS.toHours(matsimConfig.travelTimeCalculator().getMaxTime).toInt
        new BeamWarmStart(beamConfig, maxHour)
      }
      val configAgents = beamConfig.beam.agentsim.agents
      val scenarioConfig = beamConfig.beam.exchange.scenario
      val fileFormat = scenarioConfig.fileFormat
      val (plansFile: String, personAttributes) = scenarioConfig.source.toLowerCase match {
        case "urbansim"                    => "plans.csv"
        case "beam" if fileFormat == "csv" => "plans.csv"
        case "beam" if fileFormat == "xml" =>
          val populationAttributes = instance.compressedLocation("Person attributes", "outputPersonAttributes.xml.gz")
          matsimConfig.plans().setInputPersonAttributeFile(populationAttributes)

          val resultPlansFile = instance.compressedLocation("Plans", "plans.xml.gz")
          logger.info(s"** Plans file: $resultPlansFile")
          matsimConfig.plans().setInputFile(resultPlansFile)
          logger.warn("@@@@@ MATSIM INPUT FILE" + matsimConfig.plans().getInputFile)
          (resultPlansFile, populationAttributes)
        case _ =>
          throw new IllegalArgumentException(
            s"Invalid combination source: [${scenarioConfig.source}] and file format: $fileFormat"
          )
      }

      logger.warn(s"person attributes (need to update beamconfig: $personAttributes")

      val newPlans = configAgents.plans.copy(inputPersonAttributesFilePath = plansFile)
      val newConfigAgents = configAgents.copy(plans = newPlans)
      val newAgentSim = beamConfig.beam.agentsim.copy(agents = newConfigAgents)
      val newBeam = beamConfig.beam.copy(agentsim = newAgentSim)
      val newBeamConfig = beamConfig.copy(beam = newBeam)

      beamExecutionConfig.copy(beamConfig = newBeamConfig)
    } else {
      beamExecutionConfig
    }
  }
}
