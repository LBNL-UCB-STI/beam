package beam.router.r5

import beam.router.RouteHistory.LinkId
import beam.sim.config.BeamConfig

case class BikeLanesData(
  scaleFactorFromConfig: Double,
  bikeLanesLinkIds: Set[Int]
)

object BikeLanesData {

  def apply(beamConfig: BeamConfig): BikeLanesData = {
    val scaleFactorFromConfig: Double = beamConfig.beam.routing.r5.bikeLaneScaleFactor
    val bikeLanesLinkIds: Set[LinkId] = BikeLanesAdjustment.loadBikeLaneLinkIds(beamConfig)
    BikeLanesData(scaleFactorFromConfig, bikeLanesLinkIds)
  }
}
