package beam.agentsim.infrastructure.charging

case class ChargingInquiryData(data: Map[ChargingPointType, ChargingPreference]) {
  val agentMustCharge: Boolean = data.values.exists(_ == ChargingPreference.MustCharge)
}

object ChargingInquiryData {

  def apply(data: Map[ChargingPointType, ChargingPreference]): Option[ChargingInquiryData] = {
    if (data == null || data.isEmpty)
      None
    else
      Some(new ChargingInquiryData(data))
  }

}
