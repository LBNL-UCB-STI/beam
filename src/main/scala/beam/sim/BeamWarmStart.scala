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
import org.matsim.core.router.util.TravelTime

import scala.compat.java8.StreamConverters._

object BeamWarmStart {
  def apply(beamConfig: BeamConfig): BeamWarmStart = new BeamWarmStart(beamConfig)
}

class BeamWarmStart(beamConfig: BeamConfig) extends LazyLogging {
  // beamConfig.beam.warmStart.pathType=PARENT_RUN, ABSOLUTE_PATH
  private val pathType = beamConfig.beam.warmStart.pathType
  private val srcPath = beamConfig.beam.warmStart.path

  /**
    * check whether warmStart mode is enabled.
    *
    * @return true if warm start enabled, otherwise false.
    */
  val isWarmMode: Boolean = beamConfig.beam.warmStart.enabled

  /**
    * initialize warm start mode.
    */
  def init(beamRouter: ActorRef): Unit = {
    if (!isWarmMode) return

    warmStartPath match {
      case Some(statsPath) =>
        if (Files.exists(Paths.get(statsPath))) {
          beamRouter ! UpdateTravelTime(getTravelTime(statsPath))
          logger.info(s"Warm start mode initialized successfully from stats located at $statsPath.")
        } else {
          logger.warn(
            s"Warm start mode initialization failed, stats not found at path ( $statsPath )"
          )
        }
      case None =>
    }
  }

  lazy val populationFilePath: Option[String] =
    if (isWarmMode) {
      val path = pathType match {
        case "PARENT_RUN"    => parentRunPath
        case "ABSOLUTE_PATH" => srcPath
      }
      Some(loadPopulation(path, populationFile(path)))
    } else None

  private lazy val warmStartPath: Option[String] = pathType match {
    case "PARENT_RUN" =>
      getWarmStartPath(parentRunPath)

    case "ABSOLUTE_PATH" =>
      Files
        .walk(Paths.get(srcPath))
        .toScala[Stream]
        .map(_.toString)
        .find(_.endsWith(".linkstats.csv.gz"))

    case _ =>
      logger.warn(s"Warm start mode initialization failed, not a valid path type ( $pathType )")
      None
  }

  private def getWarmStartPath(runPath: String) = {
    val iterOption = Files
      .walk(Paths.get(runPath))
      .toScala[Stream]
      .map(_.toString)
      .find(p => "ITERS".equals(getName(p)))

    iterOption match {
      case Some(iterBase) =>
        getWarmStartIteration(iterBase) match {
          case Some(warmIteration) =>
            Some(
              Paths.get(iterBase, s"it.$warmIteration", s"$warmIteration.linkstats.csv.gz").toString
            )
          case None =>
            logger.warn(
              s"Warm start mode initialization failed, no iteration found with warm state in parent run ( $srcPath )"
            )
            None
        }
      case None =>
        logger.warn(
          s"Warm start mode initialization failed, ITERS not found in parent run ( $srcPath )"
        )
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
      runPath
    } else {
      srcPath
    }
  }

  private def populationFile(runPath: String): String = Paths.get(runPath, "output_plans.xml.gz").toString

  private def loadPopulation(runPath: String, populationFile: String): String = {
    val plansPath = Paths.get(runPath, "output_plans.xml")
    unGunzipFile(populationFile, plansPath.toString, false)
    plansPath.toString
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

  private def getWarmStartIteration(itrBaseDir: String): Option[Int] = {

    def getWarmStartIter(itr: Int): Int =
      if (itr < 0 || isWarmStartIteration(itrBaseDir.toString, itr)) itr
      else getWarmStartIter(itr - 1)

    val itrIndex = getWarmStartIter(new File(itrBaseDir).list().length - 1)

    Some(itrIndex).filter(_ >= 0)
  }

  private def isWarmStartIteration(itrBaseDir: String, itr: Int): Boolean = {
    val linkStats = Paths.get(itrBaseDir, s"it.$itr", s"$itr.linkstats.csv.gz")
    Files.exists(linkStats)
  }
}
