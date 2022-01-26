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
  waitTime: Int,
  costPerMile: Double,
  vehicleIsWheelchairAccessible: Boolean
) extends AbstractSkimmerEvent(eventTime) {

  override protected val skimName: String = RideHailSkimmer.name

  override val getKey: AbstractSkimmerKey =
    RidehailSkimmerKey(tazId, SkimsUtils.timeToBin(eventTime.toInt), reservationType, wheelchairRequired)

  override val getSkimmerInternal: AbstractSkimmerInternal = RidehailSkimmerInternal(waitTime, costPerMile, 0, if (vehicleIsWheelchairAccessible) 1.0 else 0.0)
}

class UnmatchedRideHailRequestSkimmerEvent(
  eventTime: Double,
  tazId: Id[TAZ],
  reservationType: RideHailReservationType,
  wheelchairRequired: Boolean
) extends AbstractSkimmerEvent(eventTime) {

  override protected val skimName: String = RideHailSkimmer.name

  override val getKey: AbstractSkimmerKey =
    RidehailSkimmerKey(tazId, SkimsUtils.timeToBin(eventTime.toInt), reservationType, wheelchairRequired)

  override val getSkimmerInternal: AbstractSkimmerInternal =
    RidehailSkimmerInternal(Double.NaN, Double.NaN, 100.0, Double.NaN)
}
