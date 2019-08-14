package beam.agentsim.infrastructure.charging

sealed trait ElectricCurrentType

object ElectricCurrentType {

  case object AC extends ElectricCurrentType
  case object DC extends ElectricCurrentType

  def apply(s: String): ElectricCurrentType = {
    s.trim.toLowerCase match {
      case "ac" => AC
      case "dc" => DC
      case _    => throw new IllegalArgumentException("invalid electric current type provided")
    }
  }

}
