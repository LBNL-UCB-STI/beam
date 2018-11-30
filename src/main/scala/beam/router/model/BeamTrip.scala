package beam.router.model

case class BeamTrip(legs: IndexedSeq[BeamLeg])

object BeamTrip {

  val empty: BeamTrip = BeamTrip(Vector())
}
