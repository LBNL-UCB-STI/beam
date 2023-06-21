package beam.router.skim.event

import beam.agentsim.events.RideHailReservationConfirmationEvent.RideHailReservationType
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.skim.SkimsUtils
import beam.router.skim.core.RideHailSkimmer.{RidehailSkimmerInternal, RidehailSkimmerKey}
import beam.router.skim.core.{AbstractSkimmerEvent, AbstractSkimmerInternal, AbstractSkimmerKey, RideHailSkimmer}
import org.matsim.api.core.v01.Id

/**
  * @author Dmitry Openkov
  */
class RideHailSkimmerEvent(
  eventTime: Double,
  tazId: Id[TAZ],
  reservationType: RideHailReservationType,
  wheelchairRequired: Boolean,
  serviceName: String,
  waitTime: Int,
  costPerMile: Double,
  vehicleIsWheelchairAccessible: Boolean,
  isReservation: Boolean
) extends AbstractSkimmerEvent(eventTime) {

  override protected val skimName: String = RideHailSkimmer.name

  override val getKey: AbstractSkimmerKey =
    RidehailSkimmerKey(tazId, SkimsUtils.timeToBin(eventTime.toInt), reservationType, wheelchairRequired, serviceName)

  override val getSkimmerInternal: AbstractSkimmerInternal =
    RidehailSkimmerInternal(
      waitTimeForRequests = if (isReservation) waitTime else 0,
      costPerMileForRequests = if (isReservation) costPerMile else 0,
      unmatchedRequestsPercent = 0.0,
      waitTimeForQuotes = waitTime,
      costPerMileForQuotes = costPerMile,
      unmatchedQuotesPercent = 0.0,
      accessibleVehiclePercent = if (vehicleIsWheelchairAccessible) 1.0 else 0.0,
      numberOfReservationsRequested = if (isReservation) 1 else 0,
      numberOfReservationsReturned = if (isReservation) 1 else 0,
      numberOfQuotesRequested = if (isReservation) 0 else 1,
      numberOfQuotesReturned = if (isReservation) 0 else 1
    )
}

class UnmatchedRideHailRequestSkimmerEvent(
  eventTime: Double,
  tazId: Id[TAZ],
  reservationType: RideHailReservationType,
  wheelchairRequired: Boolean,
  serviceName: String,
  isReservation: Boolean
) extends AbstractSkimmerEvent(eventTime) {

  override protected val skimName: String = RideHailSkimmer.name

  override val getKey: AbstractSkimmerKey =
    RidehailSkimmerKey(tazId, SkimsUtils.timeToBin(eventTime.toInt), reservationType, wheelchairRequired, serviceName)

  override val getSkimmerInternal: AbstractSkimmerInternal =
    RidehailSkimmerInternal(
      waitTimeForRequests = 0,
      costPerMileForRequests = 0,
      unmatchedRequestsPercent = if (isReservation) 100.0 else 0.0,
      waitTimeForQuotes = 0,
      costPerMileForQuotes = 0,
      unmatchedQuotesPercent = if (isReservation) 0.0 else 100.0,
      accessibleVehiclePercent = if (wheelchairRequired) 100.0 else 0.0,
      numberOfReservationsRequested = if (isReservation) 1 else 0,
      numberOfReservationsReturned = 0,
      numberOfQuotesRequested = if (isReservation) 0 else 1,
      numberOfQuotesReturned = 0
    )
}
