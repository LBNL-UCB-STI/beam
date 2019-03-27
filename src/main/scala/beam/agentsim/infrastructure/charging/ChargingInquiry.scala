package beam.agentsim.infrastructure.charging
import beam.agentsim.agents.choice.logit.MultinomialLogit
import beam.agentsim.agents.vehicles.BeamVehicle
import org.matsim.vehicles.VehicleType

case class ChargingInquiry(
  val utility: Option[MultinomialLogit[String, String]],
  val plugData: Option[List[ChargingPointType]],
  val vehicle: BeamVehicle,
  val vot: Double
)

object ChargingInquiry {

  def apply(
    utility: Option[MultinomialLogit[String, String]],
    plugData: Option[List[ChargingPointType]],
    vehicle: BeamVehicle,
    vot: Double
  ): Some[ChargingInquiry] = {
    Some(new ChargingInquiry(utility, plugData, vehicle, vot))
  }

}
