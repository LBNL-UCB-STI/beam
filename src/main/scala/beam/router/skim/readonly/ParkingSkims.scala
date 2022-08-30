package beam.router.skim.readonly

import beam.agentsim.infrastructure.taz.TAZ
import beam.router.skim.core.AbstractSkimmerReadOnly
import beam.router.skim.core.ParkingSkimmer.{ChargerType, ParkingSkimmerInternal, ParkingSkimmerKey}
import org.matsim.api.core.v01.Id

/**
  * @author Dmitry Openkov
  */
class ParkingSkims extends AbstractSkimmerReadOnly {

  def getSkimValue(
    tazId: Id[TAZ],
    hour: Int,
    chargerType: ChargerType
  ): Option[ParkingSkimmerInternal] = {
    val key = ParkingSkimmerKey(tazId, hour, chargerType)

    val getSkimValue = pastSkims
      .get(currentIteration - 1)
      .flatMap(_.get(key))
      .orElse(aggregatedFromPastSkims.get(key))
      .asInstanceOf[Option[ParkingSkimmerInternal]]

    if (getSkimValue.nonEmpty) {
      numberOfSkimValueFound = numberOfSkimValueFound + 1
    }
    numberOfRequests = numberOfRequests + 1

    getSkimValue
  }
}
