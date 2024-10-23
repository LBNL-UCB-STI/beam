package beam.router.skim.readonly

import beam.agentsim.agents.vehicles.VehicleEmissions.EmissionsProfile
import beam.router.skim.core.AbstractSkimmerReadOnly
import beam.router.skim.core.EmissionsSkimmer.{EmissionsSkimmerInternal, EmissionsSkimmerKey}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link

class EmissionsSkims() extends AbstractSkimmerReadOnly {

  def isLatestSkimEmpty: Boolean = pastSkims.isEmpty

  def getLatestSkim(
    linkId: Id[Link],
    vehicleType: String,
    hour: Int,
    tazId: String,
    emissionsProcess: EmissionsProfile.EmissionsProcess
  ): Option[EmissionsSkimmerInternal] = {
    val getSkimValue = pastSkims
      .get(currentIteration - 1)
      .flatMap(_.get(EmissionsSkimmerKey(linkId.toString, vehicleType, hour, tazId, emissionsProcess)))
      .asInstanceOf[Option[EmissionsSkimmerInternal]]
    if (getSkimValue.nonEmpty) {
      numberOfSkimValueFound = numberOfSkimValueFound + 1
    }
    numberOfRequests = numberOfRequests + 1

    getSkimValue
  }

  def getAggregatedSkim(
    linkId: Id[Link],
    vehicleType: String,
    hour: Int,
    tazId: String,
    emissionsProcess: EmissionsProfile.EmissionsProcess
  ): Option[EmissionsSkimmerInternal] =
    aggregatedFromPastSkims
      .get(EmissionsSkimmerKey(linkId.toString, vehicleType, hour, tazId, emissionsProcess))
      .asInstanceOf[Option[EmissionsSkimmerInternal]]
}
