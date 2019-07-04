package beam.sim

import java.io.File
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
import org.matsim.core.router.util.TravelTime

import scala.compat.java8.StreamConverters._

class BeamWarmStart private (beamConfig: BeamConfig, maxHour: Int) extends LazyLogging {
  val isWarmMode = beamConfig.beam.warmStart.enabled
  if (!isWarmMode) { // TODO: it will be inlined
    throw new IllegalStateException("BeamWarmStart cannot be initialized since warmstart is disabled")
  }

  val srcPath = beamConfig.beam.warmStart.path

  def warmStartTravelTime(beamRouter: ActorRef, scenario: Scenario): Unit = {
    readTravelTime.foreach { travelTime =>
      beamRouter ! UpdateTravelTimeLocal(travelTime)
      BeamWarmStart.updateRemoteRouter(scenario, travelTime, maxHour, beamRouter)
      logger.info("Travel times successfully warm started from")
    }
  }

  def readTravelTime: Option[TravelTime] = {
    getWarmStartFilePath("linkstats.csv.gz", rootFirst = false) match {
      case Some(statsPath) =>
        if (Files.isRegularFile(Paths.get(statsPath))) {
          val travelTime = getTravelTime(statsPath)
          logger.info("Read travel times from {}.", statsPath)
          Some(travelTime)
        } else {
          logger.warn("Travel times failed to warm start, stats not found at path ( {} )", statsPath)
          None
        }
      case None =>
        logger.warn("Travel times failed to warm start, stats not found at path ( {} )", srcPath)
        None
    }
  }

  private def warmstartFile(description: String, fileName: String): String = {
    println(s"@@@@@@@@@@@@@@@@@ srcPath#0: $srcPath")
    println(s"@@@@@@@@@@@@@@@@@ fileName#1: $fileName")
    getWarmStartFilePath(fileName) match {
      case Some(compressedFile) =>
        println(s"@@@@@@@@@@@@@@@@@ compressedFile#2: $compressedFile")
        if (Files.isRegularFile(Paths.get(compressedFile))) {
          println(s"@@@@@@@@@@@@@@@@@ parentRunPath#3: $parentRunPath")
          loadPopulation(parentRunPath, compressedFile)
        } else {
          throwErrorFileNotFound(description, compressedFile)
        }
      case None =>
        println("@@@@@@@@@@@@@2 File not found")
        throwErrorFileNotFound(description, srcPath)
    }
  }

  private def throwErrorFileNotFound(fileDesc: String, path: String): String = {
    throw new IllegalArgumentException(s"Warmstart configuration is invalid. [$fileDesc] not found at path [$path]")
  }

  private def loadPopulation(runPath: String, fileName: String): String = {
    val plansPath = Paths.get(runPath, "warmstart_plans.xml").toString
    unGunzipFile(fileName, plansPath, false)
    plansPath
  }

  def getWarmStartFilePath(warmStartFile: String, rootFirst: Boolean = true): Option[String] = {
    println("@@@@@@@@ getWarmStartFilePath entered")
    lazy val itrFile = findIterationWarmStartFile(warmStartFile, parentRunPath)
    lazy val rootFile = findRootWarmStartFile(warmStartFile)

    if (rootFirst) {
      println("@@@@@@@@ getWarmStartFilePath entered#1")
      rootFile.fold(itrFile)(Some(_))
    } else {
      println("@@@@@@@@ getWarmStartFilePath entered#2")
      itrFile.fold(rootFile)(Some(_))
    }
  }

  private def findRootWarmStartFile(warmStartFile: String): Option[String] = {
    println("@@@@ findRootWarmStartFile#1")
    val search = findFileInDir(warmStartFile, parentRunPath)
    println("@@@@ findRootWarmStartFile#2")

    if (search.nonEmpty) {
      println("@@@@ findRootWarmStartFile#3")
      search

    } else {
      println("@@@@ findRootWarmStartFile#4")
      val iters = getITERSPath(parentRunPath)

      println("@@@@ findRootWarmStartFile#5")
      if (iters.nonEmpty) {
        println("@@@@ findRootWarmStartFile#6")
        findFileInDir(warmStartFile, Paths.get(iters.head).getParent.toString)

      } else {
        println("@@@@ findRootWarmStartFile#7")
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

  @deprecated
  def apply(beamConfig: BeamConfig, maxHour: Int): BeamWarmStart = new BeamWarmStart(beamConfig, maxHour)

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
      val plansFile = scenarioConfig.source.toLowerCase match {
        case "urbansim"                    => "plans.csv"
        case "beam" if fileFormat == "csv" => "plans.csv"
        case "beam" if fileFormat == "xml" =>
          val resultPlansFile = instance.warmstartFile("Plans", "plans.xml.gz")
          matsimConfig.plans().setInputFile(resultPlansFile)
          resultPlansFile
        case _ =>
          throw new IllegalArgumentException(
            s"Invalid combination source: [${scenarioConfig.source}] and file format: $fileFormat"
          )
      }
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
