package beam.agentsim.infrastructure.charging

import beam.agentsim.agents.choice.logit.{MultinomialLogit, UtilityFunctionOperation}
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.infrastructure.parking.ParkingZoneSearch

@Deprecated
case class ChargingInquiry(
  utility: Option[MultinomialLogit[ParkingZoneSearch.ParkingAlternative, String]],
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
