package beam.router.skim.readonly

import beam.router.skim.core.AbstractSkimmerReadOnly
import beam.router.skim.core.TAZSkimmer.{TAZSkimmerInternal, TAZSkimmerKey}
import org.matsim.api.core.v01.Id

class TAZSkims() extends AbstractSkimmerReadOnly {

  def isLatestSkimEmpty: Boolean = pastSkims.isEmpty

  def getLatestSkim(time: Int, geoId: Id[_], actor: String, key: String): Option[TAZSkimmerInternal] = {
    val getSkimValue = pastSkims
      .get(currentIteration - 1)
      .flatMap(_.get(TAZSkimmerKey(time, geoId.toString, actor, key)))
      .asInstanceOf[Option[TAZSkimmerInternal]]
    if (getSkimValue.nonEmpty) {
      numberOfSkimValueFound = numberOfSkimValueFound + 1
    }
    numberOfRequests = numberOfRequests + 1

    getSkimValue
  }

  def getLatestSkim(time: Int, geoId: String, actor: String, key: String): Option[TAZSkimmerInternal] =
    getLatestSkim(time, geoId, actor, key)

  def getAggregatedSkim(time: Int, geoId: Id[_], actor: String, key: String): Option[TAZSkimmerInternal] =
    aggregatedFromPastSkims
      .get(TAZSkimmerKey(time, geoId.toString, actor, key))
      .asInstanceOf[Option[TAZSkimmerInternal]]
}
