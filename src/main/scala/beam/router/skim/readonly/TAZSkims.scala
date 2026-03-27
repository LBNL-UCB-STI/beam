package beam.router.skim.readonly

import beam.router.skim.core.AbstractSkimmerReadOnly
import beam.router.skim.core.TAZSkimmer.{TAZSkimmerInternal, TAZSkimmerKey}
import org.matsim.api.core.v01.Id

class TAZSkims() extends AbstractSkimmerReadOnly {

  def isLatestSkimEmpty: Boolean = isLatestPastSkimEmpty

  def getLatestSkim(time: Int, geoId: Id[_], actor: String, key: String): Option[TAZSkimmerInternal] = {
    val getSkimValue = latestPastSkimValue[TAZSkimmerInternal](TAZSkimmerKey(time, geoId.toString, actor, key))
    if (getSkimValue.nonEmpty) {
      numberOfSkimValueFound = numberOfSkimValueFound + 1
    }
    numberOfRequests = numberOfRequests + 1

    getSkimValue
  }

  def getLatestSkim(time: Int, geoId: String, actor: String, key: String): Option[TAZSkimmerInternal] =
    getLatestSkim(time, geoId, actor, key)

  def getAggregatedSkim(time: Int, geoId: Id[_], actor: String, key: String): Option[TAZSkimmerInternal] =
    aggregatedSkimValue[TAZSkimmerInternal](TAZSkimmerKey(time, geoId.toString, actor, key))
}
