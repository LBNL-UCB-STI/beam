package beam.agentsim.infrastructure.charging
import scala.collection.concurrent.TrieMap

import beam.agentsim.agents.PersonAgent.BasePersonData
import beam.agentsim.agents.choice.logit.{MultinomialLogit, UtilityFunctionOperation}
import beam.agentsim.agents.planning.Tour
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.vehicles.FuelType.{Electricity, Gasoline}
import beam.agentsim.infrastructure.parking.ParkingZoneSearch
import beam.router.BeamSkimmer
import beam.router.Modes.BeamMode.CAR
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Activity
import org.matsim.vehicles.VehicleType

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
