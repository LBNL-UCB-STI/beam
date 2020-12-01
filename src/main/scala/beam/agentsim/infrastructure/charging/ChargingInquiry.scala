package beam.agentsim.infrastructure.charging

import beam.agentsim.agents.choice.logit.{MultinomialLogit, UtilityFunctionOperation}
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.infrastructure.parking.ParkingZoneSearch

@Deprecated
case class ChargingInquiry[GEO](
  utility: Option[MultinomialLogit[ParkingZoneSearch.ParkingAlternative[GEO], String]],
  plugData: Option[List[ChargingPointType]],
  vehicle: BeamVehicle,
  vot: Double
)

object ChargingInquiry {

  def apply[GEO](
    utility: Option[MultinomialLogit[ParkingZoneSearch.ParkingAlternative[GEO], String]],
    plugData: Option[List[ChargingPointType]],
    vehicle: BeamVehicle,
    vot: Double
  ): Some[ChargingInquiry[GEO]] = {
    Some(new ChargingInquiry(utility, plugData, vehicle, vot))
  }

}
