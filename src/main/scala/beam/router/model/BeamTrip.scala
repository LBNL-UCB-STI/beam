package beam.router.model

import beam.router.Modes.BeamMode

case class BeamTrip(legs: IndexedSeq[BeamLeg], accessMode: BeamMode)

object BeamTrip {
  def apply(legs: IndexedSeq[BeamLeg]): BeamTrip = BeamTrip(legs, legs.head.mode)

  val empty: BeamTrip = BeamTrip(Vector(), BeamMode.WALK)
}
