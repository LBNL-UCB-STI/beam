package beam.router.skim

import beam.agentsim.agents.choice.mode.DrivingCost
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.router.BeamRouter.Location
import beam.router.Modes.BeamMode._
import beam.router.skim.ActivitySimSkimmer.ActivitySimSkimmerInternal
import beam.router.skim.SkimsUtils.distanceAndTime
import beam.router.skim.core.AbstractSkimmerReadOnly
import beam.sim.BeamScenario
import beam.sim.config.BeamConfig
import org.matsim.api.core.v01.Id

case class ActivitySimSkims(beamConfig: BeamConfig, beamScenario: BeamScenario) extends AbstractSkimmerReadOnly {

  def getSkimDefaultValue(
    pathType: ActivitySimPathType,
    originUTM: Location,
    destinationUTM: Location,
    departureTime: Int,
    vehicleTypeId: Id[BeamVehicleType],
    beamScenario: BeamScenario
  ): ActivitySimSkimmerInternal = {
    val beamMode = ActivitySimPathType.toBeamMode(pathType)
    val (travelDistance, travelTime) = distanceAndTime(beamMode, originUTM, destinationUTM)
    val votMultiplier: Double = beamMode match {
      case CAV => beamConfig.beam.agentsim.agents.modalBehaviors.modeVotMultiplier.CAV
      case _   => 1.0
    }
    val travelCost: Double = beamMode match {
      case CAR | CAV =>
        val vehicleType = beamScenario.vehicleTypes(vehicleTypeId)
        DrivingCost.estimateDrivingCost(
          travelDistance,
          travelTime,
          vehicleType,
          beamScenario.fuelTypePrices(vehicleType.primaryFuelType)
        )
      case RIDE_HAIL =>
        beamConfig.beam.agentsim.agents.rideHail.defaultBaseCost + beamConfig.beam.agentsim.agents.rideHail.defaultCostPerMile * travelDistance / 1609.0 + beamConfig.beam.agentsim.agents.rideHail.defaultCostPerMinute * travelTime / 60.0
      case RIDE_HAIL_POOLED =>
        beamConfig.beam.agentsim.agents.rideHail.pooledBaseCost + beamConfig.beam.agentsim.agents.rideHail.pooledCostPerMile * travelDistance / 1609.0 + beamConfig.beam.agentsim.agents.rideHail.pooledCostPerMinute * travelTime / 60.0
      case TRANSIT | WALK_TRANSIT | DRIVE_TRANSIT | RIDE_HAIL_TRANSIT => 0.25 * travelDistance / 1609
      case _                                                          => 0.0
    }
    ActivitySimSkimmerInternal(
      travelTime,
      travelTime * votMultiplier,
      travelCost + travelTime * beamConfig.beam.agentsim.agents.modalBehaviors.defaultValueOfTime / 3600,
      travelDistance,
      travelCost,
      0,
      0,
      0,
      0,
      0,
      0,
      0,
      0,
      0,
      0
    )
  }
}
