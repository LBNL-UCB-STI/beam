package beam.agentsim.infrastructure.charging
import beam.agentsim.agents.choice.logit.MultinomialLogit

case class ChargingInquiryData[A, T](utility: Option[MultinomialLogit[A, T]]) {
  val agentMustCharge: Boolean = utility match { case Some(_) => true; case None => false }

  val data
    : Map[ChargingPointType, ChargingPreference] = Map() // todo remove, currently only to prevent breaking robs code
  //todo evaluation method for parking spots with charging applying the utility function

}

object ChargingInquiryData {

  def apply[A, T](utility: Option[MultinomialLogit[A, T]]) = {
    Some(new ChargingInquiryData(utility))
  }

}
