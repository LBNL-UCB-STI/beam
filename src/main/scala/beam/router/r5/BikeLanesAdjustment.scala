package beam.router.r5

import javax.inject.Inject

import beam.agentsim.agents.vehicles.{BeamVehicleType, VehicleCategory}
import beam.agentsim.infrastructure.NetworkUtilsExtensions
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.BIKE
import beam.router.RouteHistory.LinkId
import beam.sim.config.BeamConfig

class BikeLanesAdjustment @Inject()(beamConfig: BeamConfig) {

  private val scaleFactorFromConfig: Double = beamConfig.beam.routing.r5.bikeLaneScaleFactor

  private val bikeLanesLinkIds: Set[Int] = NetworkUtilsExtensions.loadBikeLaneLinkIds(beamConfig)

  def bikeLaneScaleFactor(beamMode: BeamMode, isScaleFactorEnabled: Boolean = true): Double = {
    if (beamMode == BIKE && isScaleFactorEnabled) {
      scaleFactorFromConfig
    } else {
      1D
    }
  }

  def calculateBicycleScaleFactor(vehicleType: BeamVehicleType, linkId: LinkId): Double = {
    if (vehicleType.vehicleCategory == VehicleCategory.Bike) {
      scaleFactor(linkId)
    } else {
      1D
    }
  }

  @inline
  def scaleFactor(linkId: LinkId): Double = {
    if (bikeLanesLinkIds.contains(linkId)) {
      scaleFactorFromConfig
    } else {
      1D
    }
  }

}
