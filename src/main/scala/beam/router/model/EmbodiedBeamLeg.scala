package beam.router.model

import beam.agentsim.agents.vehicles.{BeamVehicleType, PassengerSchedule}
import beam.router.BeamRouter.Location
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.WALK
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

case class EmbodiedBeamLeg(
  beamLeg: BeamLeg,
  beamVehicleId: Id[Vehicle],
  beamVehicleTypeId: Id[BeamVehicleType],
  asDriver: Boolean,
  cost: Double,
  unbecomeDriverOnCompletion: Boolean,
  isPooledTrip: Boolean = false,
  replanningPenalty: Double = 0
) {
  val isRideHail: Boolean = beamVehicleId.toString.startsWith("rideHailVehicle")
}

object EmbodiedBeamLeg {

  def dummyLegAt(
    start: Int,
    vehicleId: Id[Vehicle],
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
