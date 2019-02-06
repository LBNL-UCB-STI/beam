package beam.agentsim.infrastructure.parking.charging

case object ChargingInquiryData {

  def apply(data: Map[ChargingPoint, ChargingPreference]): Option[ChargingInquiryData] = {
    if (data == null || data.isEmpty)
      None
    else
      Some(ChargingInquiryData(data))
  }

  case class ChargingInquiryData(data: Map[ChargingPoint, ChargingPreference]) {
    val agentMustCharge: Boolean = data.values.exists(_ == ChargingPreference.MustCharge)
  }

}
