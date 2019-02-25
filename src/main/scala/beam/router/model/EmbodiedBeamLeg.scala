package beam.router.model

import beam.agentsim.agents.vehicles.{BeamVehicleType, PassengerSchedule}
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

case class EmbodiedBeamLeg(
  beamLeg: BeamLeg,
  beamVehicleId: Id[Vehicle],
  beamVehicleTypeId: Id[BeamVehicleType],
  asDriver: Boolean,
  cost: Double,
  unbecomeDriverOnCompletion: Boolean,
  isPooledTrip: Boolean = false
) {

  val isHumanBodyVehicle: Boolean =
    BeamVehicleType.isHumanVehicle(beamVehicleId)
  val isRideHail: Boolean = BeamVehicleType.isRidehailVehicle(beamVehicleId)
}

object EmbodiedBeamLeg {

  def dummyWalkLegAt(start: Int, bodyId: Id[Vehicle], isLastLeg: Boolean): EmbodiedBeamLeg = {
    EmbodiedBeamLeg(
      BeamLeg.dummyWalk(start),
      bodyId,
      BeamVehicleType.defaultHumanBodyBeamVehicleType.id,
      asDriver = true,
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
