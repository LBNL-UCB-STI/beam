package beam.agentsim.infrastructure.parking

sealed trait ReservationAgency
object ReservationAgency {
  case object NoAgency extends ReservationAgency
  case class RideHailManager (agency: String) extends ReservationAgency

  def apply(s: String): ReservationAgency = s match {
    case "" => NoAgency
    case agencyName => RideHailManager(agencyName)
  }
}