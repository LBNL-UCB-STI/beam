package beam.agentsim.infrastructure.parking

sealed trait ParkingType
object ParkingType {
  case object Residential extends ParkingType
  case class Workplace(agencyName: String) extends ParkingType
  case object Public extends ParkingType

  def apply(s: String): ParkingType = {
    s match {
      case "Residential"   => Residential
      case "Public"        => Public
      case agencyName      => Workplace(agencyName)
    }
  }
}
