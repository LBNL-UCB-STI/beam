package beam.agentsim.infrastructure.parking.charging
import beam.agentsim.infrastructure.parking.charging.ChargingPreference.ChargingPreference

case object ChargingInquiryData {

  def apply(data: Map[ChargingPoint, ChargingPreference]) = {
    case data.isEmpty => None
    case _            => ChargingInquiryData(data)
  }

  case class ChargingInquiryData(data: Map[ChargingPoint, ChargingPreference]) {
    val agentMustCharge: Boolean = data.values.exists(_ == ChargingPreference.MustCharge)
  }

}
