package beam.agentsim.infrastructure

import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.choice.logit.{MultinomialLogit, UtilityFunctionOperation}
import beam.agentsim.infrastructure.charging.ChargingInquiry
import beam.agentsim.infrastructure.parking.ParkingZoneSearch
import beam.router.BeamRouter.Location
import beam.utils.ParkingManagerIdGenerator

/**
  * message sent from a ChoosesParking agent to a Parking Manager to request parking
  *
  * @param destinationUtm  the location where we are seeking nearby parking
  * @param activityType    the activity that the agent will partake in after parking
  * @param valueOfTime     the value of time for the requestor
  * @param utilityFunction utility function for parking alternatives
  * @param parkingDuration the duration an agent is parking for
  * @param reserveStall    whether or not we reserve a stall when we send this inquiry. used when simply requesting a cost estimate for parking.
  * @param requestId       a unique ID generated for this inquiry
  */
case class ParkingInquiry(
  destinationUtm: Location,
  activityType: String,
  valueOfTime: Double,
  utilityFunction: MultinomialLogit[ParkingZoneSearch.ParkingAlternative, String],
  parkingDuration: Double,
  vehicleType: Option[BeamVehicle] = None,
  reserveStall: Boolean = true,
  requestId: Int = ParkingManagerIdGenerator.nextId // note, this expects all Agents exist in the same JVM to rely on calling this singleton
)

object ParkingInquiry {

  val simpleDistanceAndParkingTicketEqualUtilityFunction
    : MultinomialLogit[ParkingZoneSearch.ParkingAlternative, String] =
    new MultinomialLogit[ParkingZoneSearch.ParkingAlternative, String](
      Map.empty,
      Map(
        "distanceFactor"          -> UtilityFunctionOperation.Multiplier(-1),
        "parkingCostsPriceFactor" -> UtilityFunctionOperation.Multiplier(-1)
      )
    )

  val simpleDistanceEqualUtilityFunction: MultinomialLogit[ParkingZoneSearch.ParkingAlternative, String] =
    new MultinomialLogit[ParkingZoneSearch.ParkingAlternative, String](
      Map.empty,
      Map(
        "distanceFactor" -> UtilityFunctionOperation.Multiplier(-1)
      )
    )

  def apply(
    destinationUtm: Location,
    activityType: String,
    valueOfTime: Double,
    utilityFunction: MultinomialLogit[ParkingZoneSearch.ParkingAlternative, String],
    parkingDuration: Double,
    vehicleType: Option[BeamVehicle] = None,
    reserveStall: Boolean = true,
    requestId: Int = ParkingManagerIdGenerator.nextId
  ): ParkingInquiry = {
    ParkingInquiry(
      destinationUtm,
      activityType,
      valueOfTime,
      utilityFunction,
      parkingDuration,
      vehicleType,
      reserveStall,
      requestId
    )
  }

  def apply(locationUtm: Location, activity: String): ParkingInquiry = {
    ParkingInquiry(locationUtm, activity, 0.0, simpleDistanceAndParkingTicketEqualUtilityFunction, 0, None)
  }

  def apply(locationUtm: Location, activity: String, beamVehicleOption: Option[BeamVehicle]): ParkingInquiry = {
    ParkingInquiry(locationUtm, activity, 0.0, simpleDistanceAndParkingTicketEqualUtilityFunction, 0, beamVehicleOption)
  }
}
