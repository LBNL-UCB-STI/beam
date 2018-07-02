package beam.sim

import java.io.File
import java.nio.file.{Files, Paths}

import beam.router.BeamRouter.UpdateTravelTime
import beam.router.LinkTravelTimeContainer
import com.typesafe.scalalogging.LazyLogging
import org.matsim.core.router.util.TravelTime

class BeamWarmStart(val beamServices: BeamServices) extends LazyLogging {
  private val beamConfig = beamServices.beamConfig
  private val parentRun = beamConfig.beam.warmStart.parentRun
  private val warmIteration = beamConfig.beam.warmStart.iterationToUse

  def init(): Unit = {
    if (isWarmMode) {
      if (Files.isDirectory(Paths.get(parentRun))) {
        val warmIteration = getWarmIteration
        if (warmIteration >= 0) {
          val warmPath = Paths.get(beamConfig.beam.warmStart.parentRun, "ITERS", s"it.$warmIteration").toString
          beamServices.beamRouter ! UpdateTravelTime(getWarmTravelTime(warmPath, warmIteration))
        }
      }
    } else {
      logger.warn(s"Warm mode initialization failed, not a valid parent run ( $parentRun )")
    }
  }

  def isWarmMode: Boolean = beamConfig.beam.warmStart.enabled

  def getWarmIteration: Int = {

    def getWarmIter(itr: Int): Int = if (itr < 0 || isWarmIteration(itr)) itr else getWarmIter(itr - 1)

    val itrIndex = if (warmIteration < 0) {
      val itrBaseDir = Paths.get(parentRun, "ITERS")
      getWarmIter(new File(itrBaseDir.toUri).list().length - 1)
    } else {
      if (Files.isDirectory(Paths.get(parentRun, "ITERS", s"it.$warmIteration")) &&
        isWarmIteration(warmIteration))
        warmIteration
      else
        -1
    }

    itrIndex
  }

  def isWarmIteration(itr: Int): Boolean = {
    val linkStats = Paths.get(parentRun, "ITERS", s"it.$itr", s"$itr.linkstats.csv.gz")
    Files.exists(linkStats)
  }

  def getWarmTravelTime(warmPath: String, warmIteration: Int): TravelTime = {
    val statsFile = Paths.get(warmPath, s"$warmIteration.linkstats.csv.gz").toString
    val binSize = beamConfig.beam.agentsim.agents.rideHail.surgePricing.timeBinSize

    new LinkTravelTimeContainer(statsFile, binSize)
  }
}
