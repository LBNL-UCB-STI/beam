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

    getSkimValueByKey(key)
  }
}
