package beam.router.skim

import beam.agentsim.infrastructure.taz.TAZ
import beam.router.skim.CountSkimmer.{CountSkimmerInternal, CountSkimmerKey}
import beam.sim.BeamServices
import beam.sim.vehiclesharing.VehicleManager
import org.matsim.api.core.v01.Id

case class CountSkims(beamServices: BeamServices) extends AbstractSkimmerReadOnly(beamServices) {

  def getLatestSkim(
    time: Int,
    taz: Id[TAZ],
    hex: String,
    groupId: String,
    label: String
  ): Option[CountSkimmerInternal] = {
    pastSkims.headOption
      .flatMap(_.get(CountSkimmerKey(time, taz, hex, groupId, label)))
      .asInstanceOf[Option[CountSkimmerInternal]]
  }

  def getLatestSkim(
    time: Int,
    hex: String,
    groupId: String,
    label: String
  ): Option[CountSkimmerInternal] = {
    getLatestSkim(time, beamServices.beamScenario.h3taz.getTAZ(hex), hex, groupId, label)
  }

  def getLatestSkimByTAZ(
    time: Int,
    taz: Id[TAZ],
    groupId: String,
    label: String
  ): Option[CountSkimmerInternal] = {
    beamServices.beamScenario.h3taz
      .getHRHex(taz)
      .flatMap(hex => getLatestSkim(time, taz, hex, groupId, label))
      .foldLeft[Option[CountSkimmerInternal]](None) {
        case (acc, skimInternal) =>
          acc match {
            case Some(skim) =>
              Some(
                CountSkimmerInternal(
                  sumValue = skim.sumValue + skimInternal.sumValue,
                  meanValue = (skim.meanValue * skim.numObservations + skimInternal.meanValue + skimInternal.numObservations) / (skim.numObservations + skimInternal.numObservations),
                  numObservations = skim.numObservations + skimInternal.numObservations,
                  numIteration = skim.numIteration
                )
              )
            case _ => Some(skimInternal)
          }
      }
  }

  def getAggregatedSkim(
    time: Int,
    taz: Id[TAZ],
    hex: String,
    groupid: String,
    label: String
  ): Option[CountSkimmerInternal] = {
    aggregatedSkim
      .get(CountSkimmerKey(time, taz, hex, groupid, label))
      .asInstanceOf[Option[CountSkimmerInternal]]
  }

}
