package beam.router.r5

import java.io.File
import javax.inject.Inject

import scala.util.{Failure, Success, Try}

import beam.agentsim.agents.vehicles.{BeamVehicleType, VehicleCategory}
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.BIKE
import beam.router.RouteHistory.LinkId
import beam.sim.config.BeamConfig
import beam.utils.FileUtils
import com.typesafe.scalalogging.StrictLogging
import org.jheaps.annotations.VisibleForTesting

class BikeLanesAdjustment @Inject() (bikeLanesData: BikeLanesData) {
  private val scaleFactorFromConfig = bikeLanesData.scaleFactorFromConfig
  private val bikeLanesLinkIds = bikeLanesData.bikeLanesLinkIds

  def scaleFactor(beamMode: BeamMode, isScaleFactorEnabled: Boolean = true): Double = {
    if (beamMode == BIKE && isScaleFactorEnabled) {
      scaleFactorFromConfig
    } else {
      1d
    }
  }

  def scaleFactor(vehicleType: BeamVehicleType, linkId: LinkId): Double = {
    if (vehicleType.vehicleCategory == VehicleCategory.Bike) {
      scaleFactor(linkId)
    } else {
      1d
    }
  }

  @inline
  def scaleFactor(linkId: LinkId): Double = {
    if (bikeLanesLinkIds.contains(linkId)) {
      scaleFactorFromConfig
    } else {
      1d
    }
  }

}

object BikeLanesAdjustment extends StrictLogging {

  def apply(config: BeamConfig): BikeLanesAdjustment = {
    new BikeLanesAdjustment(BikeLanesData(beamConfig = config))
  }

  @VisibleForTesting
  private[r5] def loadBikeLaneLinkIds(beamConfig: BeamConfig): Set[Int] = {
    val bikeLaneLinkIdsPath: String = beamConfig.beam.routing.r5.bikeLaneLinkIdsFilePath
    loadBikeLaneLinkIds(bikeLaneLinkIdsPath)
  }

  @VisibleForTesting
  private[r5] def loadBikeLaneLinkIds(bikeLaneLinkIdsPath: String): Set[Int] = {
    Try {
      val result: Set[String] = {
        if (new File(bikeLaneLinkIdsPath).isFile) {
          FileUtils.readAllLines(bikeLaneLinkIdsPath).toSet
        } else {
          Set.empty
        }
      }
      result.flatMap(str => Try(Some(str.toInt)).getOrElse(None))
    } match {
      case Failure(exception) =>
        logger.error("Could not load the bikeLaneLinkIds", exception)
        Set.empty
      case Success(value) => value
    }
  }

}
