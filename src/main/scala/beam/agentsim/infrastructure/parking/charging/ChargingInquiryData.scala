package beam.agentsim.infrastructure.parking.charging

case class ChargingInquiryData(private[ChargingInquiryData] val data: Map[ChargingPoint, ChargingPreference]) {
  val agentMustCharge: Boolean = data.values.exists(_ == ChargingPreference.MustCharge)
}

object ChargingInquiryData {

  def apply(data: Map[ChargingPoint, ChargingPreference]): Option[ChargingInquiryData] = {
    if (data == null || data.isEmpty)
      None
    else
      Some(new ChargingInquiryData(data))
  }

}
