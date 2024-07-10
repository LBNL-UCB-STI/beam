package beam.router.skim.readonly

import beam.agentsim.infrastructure.taz.TAZ
import beam.router.skim.core.AbstractSkimmerReadOnly
import beam.router.skim.core.FreightSkimmer.{FreightSkimmerInternal, FreightSkimmerKey}
import org.matsim.api.core.v01.Id

/**
  * @author Dmitry Openkov
  */
class FreightSkims extends AbstractSkimmerReadOnly {

  def getSkimValue(
    tazId: Id[TAZ],
    hour: Int
  ): Option[FreightSkimmerInternal] = {
    val key = FreightSkimmerKey(tazId, hour)

    getSkimValueByKey(key)
  }
}
