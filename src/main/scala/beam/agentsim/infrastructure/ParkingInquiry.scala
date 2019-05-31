package beam.agentsim.infrastructure
import beam.agentsim.infrastructure.charging.ChargingInquiry
import beam.router.BeamRouter.Location
import beam.utils.ParkingManagerIdGenerator

/**
  * message sent from a ChoosesParking agent to a Parking Manager to request parking
  * @param destinationUtm the location where we are seeking nearby parking
  * @param activityType the activity that the agent will partake in after parking
  * @param valueOfTime the value of time for the requestor
  * @param chargingInquiry utility function for parking alternatives
  * @param parkingDuration the duration an agent is parking for
  * @param reserveStall whether or not we reserve a stall when we send this inquiry. used when simply requesting a cost estimate for parking.
  */
case class ParkingInquiry(
  destinationUtm: Location,
  activityType: String,
  valueOfTime: Double,
  chargingInquiry: Option[ChargingInquiry],
  parkingDuration: Double,
  reserveStall: Boolean = true,
  requestId: Int = ParkingManagerIdGenerator.nextId // note, this expects all Agents exist in the same JVM to rely on calling this singleton
)
