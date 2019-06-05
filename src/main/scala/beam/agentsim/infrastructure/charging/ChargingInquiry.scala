package beam.agentsim.infrastructure.charging
import beam.agentsim.agents.choice.logit.MultinomialLogit
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.infrastructure.parking.ParkingZoneSearch
import org.matsim.vehicles.VehicleType

case class ChargingInquiry(
  utility: Option[MultinomialLogit[ParkingZoneSearch.ParkingAlternative, String]], // todo JH move to ParkingInquiry and make it non optional
  plugData: Option[List[ChargingPointType]],
  vehicle: BeamVehicle,
  vot: Double
)

object ChargingInquiry {

  def apply(
    utility: Option[MultinomialLogit[ParkingZoneSearch.ParkingAlternative, String]],
    plugData: Option[List[ChargingPointType]],
    vehicle: BeamVehicle,
    vot: Double
  ): Some[ChargingInquiry] = {
    Some(new ChargingInquiry(utility, plugData, vehicle, vot))
  }

}
