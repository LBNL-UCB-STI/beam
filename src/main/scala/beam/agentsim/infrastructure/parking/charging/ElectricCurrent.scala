package beam.agentsim.infrastructure.parking.charging

sealed trait ElectricCurrent
case object ElectricCurrent {

  case object AC extends ElectricCurrent
  case object DC extends ElectricCurrent

  def apply(s: String) = {
    s match {
      case "AC" || "ac" => AC
      case "DC" || "dc" => DC
      case _            => throw new IllegalArgumentException("invalid electric current type provided")
    }
  }

}
