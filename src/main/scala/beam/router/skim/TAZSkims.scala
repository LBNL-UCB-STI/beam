package beam.router.skim

import beam.agentsim.infrastructure.taz.TAZ
import beam.router.skim.TAZSkimmer.{TAZSkimmerInternal, TAZSkimmerKey}
import beam.sim.BeamServices
import org.matsim.api.core.v01.Id

case class TAZSkims(beamServices: BeamServices) extends AbstractSkimmerReadOnly(beamServices) {

  def getLatestSkim(
    time: Int,
    taz: Id[TAZ],
    hex: String,
    actor: String,
    key: String
  ): Option[TAZSkimmerInternal] = {
    pastSkims.headOption
      .flatMap(_.get(TAZSkimmerKey(time, taz, hex, actor, key)))
      .asInstanceOf[Option[TAZSkimmerInternal]]
  }

  def getLatestSkim(
    time: Int,
    hex: String,
    actor: String,
    key: String
  ): Option[TAZSkimmerInternal] = {
    getLatestSkim(time, beamServices.beamScenario.h3taz.getTAZ(hex), hex, actor, key)
  }

  def getLatestSkimByTAZ(
    time: Int,
    taz: Id[TAZ],
    actor: String,
    key: String
  ): Option[TAZSkimmerInternal] = {
    beamServices.beamScenario.h3taz
      .getIndices(taz)
      .flatMap(hex => getLatestSkim(time, taz, hex, actor, key))
      .foldLeft[Option[TAZSkimmerInternal]](None) {
        case (acc, skimInternal) =>
          acc match {
            case Some(skim) =>
              Some(
                TAZSkimmerInternal(
                  value = (skim.value * skim.observations + skimInternal.value + skimInternal.observations) / (skim.observations + skimInternal.observations),
                  observations = skim.observations + skimInternal.observations,
                  iterations = skim.iterations
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
    actor: String,
    key: String
  ): Option[TAZSkimmerInternal] = {
    aggregatedSkim
      .get(TAZSkimmerKey(time, taz, hex, actor, key))
      .asInstanceOf[Option[TAZSkimmerInternal]]
  }

}
