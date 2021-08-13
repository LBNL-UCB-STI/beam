package beam.agentsim.infrastructure.parking

sealed trait ParkingType

object ParkingType {

  case object Residential extends ParkingType {
    override def toString: String = "Residential"
  }

  case object Workplace extends ParkingType {
    override def toString: String = "Workplace"
  }

  case object Public extends ParkingType {
    override def toString: String = "Public"
  }

  def apply(s: String): ParkingType = {
    s match {
      case "Residential" => Residential
      case "Public"      => Public
      case "Workplace"   => Workplace
    }
  }

  def AllTypes: Seq[ParkingType] = Seq(Residential, Workplace, Public)
}
