package beam.agentsim.infrastructure.charging

sealed trait ElectricCurrent

object ElectricCurrent {

  case object AC extends ElectricCurrent
  case object DC extends ElectricCurrent

  def apply(s: String): ElectricCurrent = {
    s.toLowerCase match {
      case "ac" => AC
      case "dc" => DC
      case _    => throw new IllegalArgumentException("invalid electric current type provided")
    }
  }

}
