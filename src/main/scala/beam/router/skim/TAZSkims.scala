package beam.router.skim

import beam.agentsim.infrastructure.taz.TAZ
import beam.router.skim.TAZSkimmer.{TAZSkimmerInternal, TAZSkimmerKey}
import beam.sim.BeamServices
import beam.sim.vehiclesharing.VehicleManager
import org.matsim.api.core.v01.Id

case class TAZSkims(beamServices: BeamServices) extends AbstractSkimmerReadOnly(beamServices) {

  def getLatestSkim(
    time: Int,
    taz: Id[TAZ],
    hex: String,
    groupId: String,
    label: String
  ): Option[TAZSkimmerInternal] = {
    pastSkims.headOption
      .flatMap(_.get(TAZSkimmerKey(time, taz, hex, groupId, label)))
      .asInstanceOf[Option[TAZSkimmerInternal]]
  }

  def getLatestSkim(
    time: Int,
    hex: String,
    groupId: String,
    label: String
  ): Option[TAZSkimmerInternal] = {
    getLatestSkim(time, beamServices.beamScenario.h3taz.getTAZ(hex), hex, groupId, label)
  }

  def getLatestSkimByTAZ(
    time: Int,
    taz: Id[TAZ],
    groupId: String,
    label: String
  ): Option[TAZSkimmerInternal] = {
    beamServices.beamScenario.h3taz
      .getHRHex(taz)
      .flatMap(hex => getLatestSkim(time, taz, hex, groupId, label))
      .foldLeft[Option[TAZSkimmerInternal]](None) {
        case (acc, skimInternal) =>
          acc match {
            case Some(skim) =>
              Some(
                TAZSkimmerInternal(
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
  ): Option[TAZSkimmerInternal] = {
    aggregatedSkim
      .get(TAZSkimmerKey(time, taz, hex, groupid, label))
      .asInstanceOf[Option[TAZSkimmerInternal]]
  }

}
