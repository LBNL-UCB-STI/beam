package beam.sim

import java.io.File
import java.nio.file.{Files, Paths}

import akka.actor.ActorRef
import beam.router.BeamRouter.{UpdateTravelTimeLocal, UpdateTravelTimeRemote}
import beam.router.LinkTravelTimeContainer
import beam.sim.config.BeamConfig
import beam.utils.FileUtils.downloadFile
import beam.utils.TravelTimeCalculatorHelper
import beam.utils.UnzipUtility._
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.io.FileUtils.getTempDirectoryPath
import org.apache.commons.io.FilenameUtils.{getBaseName, getExtension, getName}
import org.matsim.api.core.v01.Scenario
import org.matsim.core.config.Config
import org.matsim.core.router.util.TravelTime

import scala.compat.java8.StreamConverters._

class BeamWarmStart private (beamConfig: BeamConfig, maxHour: Int) extends LazyLogging {

  val srcPath = beamConfig.beam.warmStart.path

  /**
    * check whether warmStart mode is enabled.
    *
    * @return true if warm start enabled, otherwise false.
    */
  val isWarmMode: Boolean = beamConfig.beam.warmStart.enabled

  /**
    * initialize travel times.
    */
  def warmStartTravelTime(beamRouter: ActorRef, scenario: Scenario): Unit = {
    if (isWarmMode) {
      read.foreach { travelTime =>
        beamRouter ! UpdateTravelTimeLocal(travelTime)
        BeamWarmStart.updateRemoteRouter(scenario, travelTime, maxHour, beamRouter)
        logger.info("Travel times successfully warm started from")
      }
    }
  }

  def read: Option[TravelTime] = {
    if (isWarmMode) {
      getWarmStartFilePath("linkstats.csv.gz", rootFirst = false) match {
        case Some(statsPath) =>
          if (Files.exists(Paths.get(statsPath))) {
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
    } else {
      None
    }
  }

  /**
    * initialize population.
    */
  def warmStartPopulation(matsimConfig: Config): Unit = {
    if (isWarmMode) {
      getWarmStartFilePath("plans.xml.gz") match {
        case Some(statsPath) =>
          if (Files.exists(Paths.get(statsPath))) {
            val file = loadPopulation(parentRunPath, statsPath)
            matsimConfig.plans().setInputFile(file)
            logger.info("Population successfully warm started from {}", statsPath)
          } else {
            logger.warn(
              "Population failed to warm start, plans not found at path ( {} )",
              statsPath
            )
          }
        case None =>
          logger.warn(
            "Population failed to warm start, plans not found at path ( {} )",
            srcPath
          )
      }
    }
  }

  private def loadPopulation(runPath: String, populationFile: String): String = {
    val plansPath = Paths.get(runPath, "warmstart_plans.xml")
    unGunzipFile(populationFile, plansPath.toString, false)
    plansPath.toString
  }

  def getWarmStartFilePath(warmStartFile: String, rootFirst: Boolean = true): Option[String] = {
    lazy val itrFile = findIterationWarmStartFile(warmStartFile, parentRunPath)
    lazy val rootFile = findRootWarmStartFile(warmStartFile)

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
    Files.exists(linkStats)
  }

  private def getITERSPath(runPath: String): Option[String] = {
    Files
      .walk(Paths.get(runPath))
      .toScala[Stream]
      .map(_.toString)
      .find(p => "ITERS".equals(getName(p)))
  }

  private def findFileInDir(file: String, dir: String): Option[String] =
    new File(dir).listFiles().map(_.getAbsolutePath).find(_.endsWith(file))

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

object BeamWarmStart {

  def apply(beamConfig: BeamConfig, maxHour: Int): BeamWarmStart = new BeamWarmStart(beamConfig, maxHour)

  def updateRemoteRouter(scenario: Scenario, travelTime: TravelTime, maxHour: Int, beamRouter: ActorRef): Unit = {
    val map = TravelTimeCalculatorHelper.GetLinkIdToTravelTimeArray(
      scenario.getNetwork.getLinks.values(),
      travelTime,
      maxHour
    )
    beamRouter ! UpdateTravelTimeRemote(map)
  }
}
