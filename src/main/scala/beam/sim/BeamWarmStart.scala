package beam.sim

import java.io.File
import java.nio.file.{Files, Paths}

import beam.router.BeamRouter.UpdateTravelTime
import beam.router.LinkTravelTimeContainer
import beam.utils.FileUtils.downloadFile
import beam.utils.UnzipUtility.unzip
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.io.FileUtils.getTempDirectoryPath
import org.apache.commons.io.FilenameUtils.{getBaseName, getExtension, getName}
import org.matsim.core.router.util.TravelTime

import scala.compat.java8.StreamConverters._

class BeamWarmStart(val beamServices: BeamServices) extends LazyLogging {
  private val beamConfig = beamServices.beamConfig
  // beamConfig.beam.warmStart.pathType=PARENT_RUN, ABSOLUTE_PATH
  private val pathType = beamConfig.beam.warmStart.pathType
  private val srcPath = beamConfig.beam.warmStart.path

  val isWarmMode: Boolean = beamConfig.beam.warmStart.enabled

  def init(): Unit = {
    if (isWarmMode) {

      val warmStartPath = pathType match {
        case "PARENT_RUN" =>
          val runPath = if (isZipArchive(srcPath)) {
            var archivePath = srcPath
            if (isS3Url(srcPath)) {
              archivePath = Paths.get(getTempDirectoryPath, getName(srcPath)).toString
              downloadFile(srcPath, archivePath)
            }
            val runPath = Paths.get(getTempDirectoryPath, getBaseName(srcPath)).toString
            unzip(archivePath, runPath, false)
            runPath
          } else {
            srcPath
          }

          val iterOption = Files.walk(Paths.get(runPath)).toScala[Stream].map(_.toString).find(p => "ITERS".equals(getName(p)))

          iterOption match {
            case Some(iterBase) =>

              getWarmStartIteration(iterBase) match {
                case Some(warmIteration) =>
                  Some(Paths.get(iterBase, s"it.$warmIteration", s"$warmIteration.linkstats.csv.gz").toString)
                case None =>
                  logger.warn(s"Warm mode initialization failed, no iteration found with warm state in parent run ( $srcPath )")
                  None
              }
            case None =>
              logger.warn(s"Warm mode initialization failed, ITERS not found in parent run ( $srcPath )")
              None
          }

        case "ABSOLUTE_PATH" =>
          Files.walk(Paths.get(srcPath)).toScala[Stream].map(_.toString).find(_.endsWith(".linkstats.csv.gz"))

        case _ =>
          logger.warn(s"Warm mode initialization failed, not a valid path type ( $pathType )")
          None
      }

      warmStartPath match {
        case Some(warmStatsPath) if Files.exists(Paths.get(warmStatsPath)) =>
          beamServices.beamRouter ! UpdateTravelTime(getTravelTime(warmStatsPath))
          logger.info(s"Warm mode initialized successfully with ( $warmStartPath ) stats.")

        case Some(warmStatsPath) =>
          logger.warn(s"Warm mode initialization failed, stats not found at path ( $warmStatsPath )")

        case None =>
      }
    }
  }

  private def isS3Url(source: String): Boolean = {
    assert(source != null)
    source.startsWith("https://s3.us-east-2.amazonaws.com/beam-outputs/")
  }

  private def isZipArchive(source: String): Boolean = {
    assert(source != null)
    "zip".equalsIgnoreCase(getExtension(source))
  }

  private def getTravelTime(statsFile: String): TravelTime = {
    val binSize = beamConfig.beam.agentsim.agents.rideHail.surgePricing.timeBinSize

    new LinkTravelTimeContainer(statsFile, binSize)
  }

  private def getWarmStartIteration(itrBaseDir: String): Option[Int] = {

    def getWarmStartIter(itr: Int): Int = if (itr < 0 || isWarmStartIteration(itrBaseDir.toString, itr)) itr else getWarmStartIter(itr - 1)

    val itrIndex = getWarmStartIter(new File(itrBaseDir).list().length - 1)

    Some(itrIndex).filter(_ >= 0)
  }

  private def isWarmStartIteration(itrBaseDir: String, itr: Int): Boolean = {
    val linkStats = Paths.get(itrBaseDir, s"it.$itr", s"$itr.linkstats.csv.gz")
    Files.exists(linkStats)
  }
}
