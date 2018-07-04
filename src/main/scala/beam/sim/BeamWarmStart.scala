package beam.sim

import java.io.File
import java.nio.file.{Files, Paths}

import beam.router.BeamRouter.UpdateTravelTime
import beam.router.LinkTravelTimeContainer
import beam.utils.FileUtils.downloadFile
import beam.utils.UnzipUtility
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.io.FileUtils.getTempDirectoryPath
import org.apache.commons.io.FilenameUtils
import org.apache.commons.io.FilenameUtils.getExtension
import org.matsim.core.router.util.TravelTime

class BeamWarmStart(val beamServices: BeamServices) extends LazyLogging {
  private val beamConfig = beamServices.beamConfig
  // beamConfig.beam.warmStart.pathType=PARENT_RUN, ABSOLUTE_PATH
  private val pathType = beamConfig.beam.warmStart.pathType
  private val srcPath = beamConfig.beam.warmStart.path

  def init(): Unit = {
    if (isWarmMode) {

      val warmPath = pathType match {
        case "PARENT_RUN" =>
          var runPath = srcPath
          if (isArchive(srcPath)) {
            var archivePath = srcPath
            if (isS3Url(srcPath)) {
              archivePath = Paths.get(getTempDirectoryPath, FilenameUtils.getName(srcPath)).toString
              downloadFile(srcPath, archivePath)
            }
            runPath = Paths.get(getTempDirectoryPath, FilenameUtils.getBaseName(srcPath)).toString
            UnzipUtility.unzip(archivePath, getTempDirectoryPath, true)
          }

          val warmIteration = getWarmIteration(runPath)
          if (warmIteration >= 0) {
            Paths.get(runPath, "ITERS", s"it.$warmIteration", s"$warmIteration.linkstats.csv.gz").toString
          } else {
            logger.warn(s"Warm mode initialization failed, not a valid parent run ( $srcPath )")
            null
          }
        case "ABSOLUTE_PATH" =>
          srcPath
        case _ =>
          logger.warn(s"Warm mode initialization failed, not a valid path type ( $pathType )")
          null
      }

      if (warmPath != null) {
        if (Files.exists(Paths.get(warmPath))) {
          beamServices.beamRouter ! UpdateTravelTime(getWarmTravelTime(warmPath))
        } else {
          logger.warn(s"Warm mode initialization failed, stats not found at path ( $warmPath )")
        }
      }
    }
  }

  def isWarmMode: Boolean = beamConfig.beam.warmStart.enabled

  private def isS3Url(source: String): Boolean = {
    assert(source != null)
    source.startsWith("https://s3.us-east-2.amazonaws.com/beam-outputs/")
  }

  private def isArchive(source: String): Boolean = {
    assert(source != null)
    "zip".equalsIgnoreCase(getExtension(source))
  }

  private def getWarmTravelTime(statsFile: String): TravelTime = {
    val binSize = beamConfig.beam.agentsim.agents.rideHail.surgePricing.timeBinSize

    new LinkTravelTimeContainer(statsFile, binSize)
  }

  private def getWarmIteration(parentRun: String): Int = {

    val itrBaseDir = Paths.get(parentRun, "ITERS")

    def getWarmIter(itr: Int): Int = if (itr < 0 || isWarmIteration(itrBaseDir.toString, itr)) itr else getWarmIter(itr - 1)

    val itrIndex = getWarmIter(new File(itrBaseDir.toUri).list().length - 1)

    itrIndex
  }

  private def isWarmIteration(itrBaseDir: String, itr: Int): Boolean = {
    val linkStats = Paths.get(itrBaseDir, s"it.$itr", s"$itr.linkstats.csv.gz")
    Files.exists(linkStats)
  }
}
