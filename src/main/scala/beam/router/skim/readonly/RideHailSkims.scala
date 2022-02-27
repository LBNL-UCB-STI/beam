package beam.router.skim.readonly

import beam.agentsim.events.RideHailReservationConfirmationEvent.RideHailReservationType
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.skim.core.AbstractSkimmerReadOnly
import beam.router.skim.core.RideHailSkimmer.{RidehailSkimmerInternal, RidehailSkimmerKey}
import org.matsim.api.core.v01.Id

/**
  * @author Dmitry Openkov
  */
class RideHailSkims extends AbstractSkimmerReadOnly {

  def getSkimValue(
    tazId: Id[TAZ],
    hour: Int,
    reservationType: RideHailReservationType
  ): Option[RidehailSkimmerInternal] = {
    val key = RidehailSkimmerKey(tazId, hour, reservationType)

    pastSkims
      .get(currentIteration - 1)
      .flatMap(_.get(key))
      .orElse(aggregatedFromPastSkims.get(key))
      .asInstanceOf[Option[RidehailSkimmerInternal]]
  }
}
