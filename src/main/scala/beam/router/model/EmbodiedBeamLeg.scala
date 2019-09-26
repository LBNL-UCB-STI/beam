package beam.router.model

import beam.agentsim.agents.vehicles.{BeamVehicleId, BeamVehicleType, PassengerSchedule}
import beam.router.BeamRouter.Location
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.WALK
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

case class EmbodiedBeamLeg(
  beamLeg: BeamLeg,
  beamVehicleId: BeamVehicleId,
  beamVehicleTypeId: Id[BeamVehicleType],
  asDriver: Boolean,
  cost: Double,
  unbecomeDriverOnCompletion: Boolean,
  isPooledTrip: Boolean = false,
  replanningPenalty: Double = 0
) {
  // TODO FIXME
  val isRideHail: Boolean = beamVehicleId.id.toString.startsWith("rideHailVehicle")
}

object EmbodiedBeamLeg {

  def dummyLegAt(
    start: Int,
    vehicleId: BeamVehicleId,
    isLastLeg: Boolean,
    location: Location,
    mode: BeamMode,
    vehicleTypeId: Id[BeamVehicleType],
    asDriver: Boolean = true
  ): EmbodiedBeamLeg = {
    EmbodiedBeamLeg(
      BeamLeg.dummyLeg(start, location, mode),
      vehicleId,
      vehicleTypeId,
      asDriver,
      0,
      unbecomeDriverOnCompletion = isLastLeg
    )
  }

  def makeLegsConsistent(legs: Vector[EmbodiedBeamLeg]): Vector[EmbodiedBeamLeg] = {
    var runningStartTime = legs.head.beamLeg.startTime
    for (leg <- legs) yield {
      val newLeg = leg.copy(beamLeg = leg.beamLeg.updateStartTime(runningStartTime))
      runningStartTime = newLeg.beamLeg.endTime
      newLeg
    }
  }
}
