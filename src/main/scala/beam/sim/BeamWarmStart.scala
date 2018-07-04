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
              archivePath = Paths.get(getTempDirectoryPath, getName(srcPath)).toString
              downloadFile(srcPath, archivePath)
            }
            runPath = Paths.get(getTempDirectoryPath, getBaseName(srcPath)).toString
            unzip(archivePath, runPath, false)
          }
          val optionalPath = Files.walk(Paths.get(runPath)).filter(p => "ITERS".equals(getName(p.toString))).findFirst()

          if(optionalPath.isPresent) {
            runPath = optionalPath.get().toString
            getWarmIteration(runPath) match {
              case Some(warmIteration) =>
                Some(Paths.get(runPath, s"it.$warmIteration", s"$warmIteration.linkstats.csv.gz").toString)
              case None =>
                logger.warn(s"Warm mode initialization failed, no iteration found with warm state in parent run ( $srcPath )")
                None
            }
          } else {
            logger.warn(s"Warm mode initialization failed, ITERS not found in parent run ( $srcPath )")
            None
          }

        case "ABSOLUTE_PATH" =>
          Some(srcPath)
        case _ =>
          logger.warn(s"Warm mode initialization failed, not a valid path type ( $pathType )")
          None
      }

      warmPath match {
        case Some(warmStatsPath) if Files.exists(Paths.get(warmStatsPath)) =>
          beamServices.beamRouter ! UpdateTravelTime(getWarmTravelTime(warmStatsPath))
          logger.info(s"Warm mode initialized successfully with ( $warmPath ) stats.")
        case Some(warmStatsPath) =>
          logger.warn(s"Warm mode initialization failed, stats not found at path ( $warmStatsPath )")
        case None =>
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

  private def getWarmIteration(itrBaseDir: String): Option[Int] = {

    def getWarmIter(itr: Int): Int = if (itr < 0 || isWarmIteration(itrBaseDir.toString, itr)) itr else getWarmIter(itr - 1)

    val itrIndex = getWarmIter(new File(itrBaseDir).list().length - 1)

    Some(itrIndex).filter(_ >= 0)
  }

  private def isWarmIteration(itrBaseDir: String, itr: Int): Boolean = {
    val linkStats = Paths.get(itrBaseDir, s"it.$itr", s"$itr.linkstats.csv.gz")
    Files.exists(linkStats)
  }
}
