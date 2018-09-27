package beam.sim

import java.io.File
import java.nio.file.{Files, Paths}

import akka.actor.ActorRef
import beam.router.BeamRouter.UpdateTravelTime
import beam.router.LinkTravelTimeContainer
import beam.sim.config.BeamConfig
import beam.utils.FileUtils.downloadFile
import beam.utils.UnzipUtility._
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.io.FileUtils.getTempDirectoryPath
import org.apache.commons.io.FilenameUtils.{getBaseName, getExtension, getName}
import org.matsim.core.config.Config
import org.matsim.core.router.util.TravelTime

import scala.compat.java8.StreamConverters._

object BeamWarmStart {
  private var _instance: BeamWarmStart = _
  def apply(beamConfig: BeamConfig): BeamWarmStart = {
    if(_instance == null)
      _instance = new BeamWarmStart(beamConfig)
    _instance
  }
}

class BeamWarmStart private(beamConfig: BeamConfig) extends LazyLogging {
  // beamConfig.beam.warmStart.pathType=PARENT_RUN, ABSOLUTE_PATH
  private lazy val pathType = beamConfig.beam.warmStart.pathType
  private lazy val srcPath = beamConfig.beam.warmStart.path

  /**
    * check whether warmStart mode is enabled.
    *
    * @return true if warm start enabled, otherwise false.
    */
  private lazy val isWarmMode: Boolean = beamConfig.beam.warmStart.enabled

  /**
    * initialize travel times.
    */
  def warmStartTravelTime(beamRouter: ActorRef): Unit = {
    if (!isWarmMode) return
    getWarmStartFilePath("linkstats.csv.gz") match {
      case Some(statsPath) =>
        if (Files.exists(Paths.get(statsPath))) {
          beamRouter ! UpdateTravelTime(getTravelTime(statsPath))
          logger.info("Travel times successfully warm started from {}.", statsPath)
        } else {
          logger.warn(
            "Travel times failed to warm start, stats not found at path ( {} )",
            statsPath
          )
        }
      case None =>
        logger.warn(
          "Travel times failed to warm start, stats not found at path ( {} )",
          srcPath
        )
    }
  }

  /**
    * initialize population.
    */
  def warmStartPopulation(matsimConfig: Config): Unit = {
    if (!isWarmMode) return
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

  private def loadPopulation(runPath: String, populationFile: String): String = {
    val plansPath = Paths.get(runPath, "warmstart_plans.xml")
    unGunzipFile(populationFile, plansPath.toString, false)
    plansPath.toString
  }

  private def getWarmStartFilePath(warmStartFile: String): Option[String] = pathType match {
    case "PARENT_RUN" =>
      getIterationFilePath(warmStartFile, parentRunPath).fold(findWarmStartFile(warmStartFile))(Some(_))

    case "ABSOLUTE_PATH" =>
      findWarmStartFile(warmStartFile)

    case _ =>
      None
  }

  private def findWarmStartFile(warmStartFile: String) = {
    val search = Files
      .walk(Paths.get(parentRunPath))
      .toScala[Stream]
      .map(_.toString)
      .filter(_.endsWith(warmStartFile))
    search.lastOption
  }

  private def getIterationFilePath(itFile: String, runPath: String): Option[String] = {
    val iterOption = Files
      .walk(Paths.get(runPath))
      .toScala[Stream]
      .map(_.toString)
      .find(p => "ITERS".equals(getName(p)))

    iterOption match {
      case Some(iterBase) =>
        getIterationThatContains(s".$itFile", iterBase) match {
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

  private lazy val parentRunPath = {
    if (isZipArchive(srcPath)) {
      var archivePath = srcPath
      if (isOutputBucketUrl(srcPath)) {
        archivePath = Paths.get(getTempDirectoryPath, getName(srcPath)).toString
        downloadFile(srcPath, archivePath)
      }
      val runPath = Paths.get(getTempDirectoryPath, getBaseName(srcPath)).toString
      unzip(archivePath, runPath, false)
      Paths.get(runPath, getBaseName(srcPath)).toString
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

    new LinkTravelTimeContainer(statsFile, binSize)
  }

  private def getIterationThatContains(itFile: String, itrBaseDir: String): Option[Int] = {

    def getWarmStartIter(itr: Int): Int =
      if (itr < 0 || isIterationFilePresent(itFile, itrBaseDir.toString, itr)) itr
      else getWarmStartIter(itr - 1)

    val itrIndex = getWarmStartIter(new File(itrBaseDir).list().length - 1)

    Some(itrIndex).filter(_ >= 0)
  }

  private def isIterationFilePresent(itFile: String, itrBaseDir: String, itr: Int): Boolean = {
    val linkStats = Paths.get(itrBaseDir, s"it.$itr", s"$itr$itFile")
    Files.exists(linkStats)
  }
}
