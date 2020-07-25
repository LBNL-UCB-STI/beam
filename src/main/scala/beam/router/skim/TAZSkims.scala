package beam.router.skim

import beam.agentsim.infrastructure.taz.TAZ
import beam.router.skim.TAZSkimmer.{TAZSkimmerInternal, TAZSkimmerKey}
import beam.sim.BeamScenario
import beam.sim.config.BeamConfig
import org.matsim.api.core.v01.Id

case class TAZSkims(beamConfig: BeamConfig, beamScenario: BeamScenario) extends AbstractSkimmerReadOnly(beamConfig) {

  override protected val skimTimeBin: Int = beamConfig.beam.router.skim.taz_skimmer.timeBin

  def getLatestSkim(
    time: Int,
    taz: Id[TAZ],
    hex: String,
    actor: String,
    key: String
  ): Option[TAZSkimmerInternal] = {
    pastSkims.headOption
      .flatMap(_.get(TAZSkimmerKey(toTimeBin(time), taz, hex, actor, key)))
      .asInstanceOf[Option[TAZSkimmerInternal]]
  }

  def getLatestSkim(
    time: Int,
    hex: String,
    actor: String,
    key: String
  ): Option[TAZSkimmerInternal] = {
    getLatestSkim(toTimeBin(time), beamScenario.h3taz.getTAZ(hex), hex, actor, key)
  }

  def getLatestSkimByTAZ(
    time: Int,
    taz: Id[TAZ],
    actor: String,
    key: String
  ): Option[TAZSkimmerInternal] = {
    beamScenario.h3taz
      .getIndices(taz)
      .flatMap(hex => getLatestSkim(toTimeBin(time), taz, hex, actor, key))
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
      .get(TAZSkimmerKey(toTimeBin(time), taz, hex, actor, key))
      .asInstanceOf[Option[TAZSkimmerInternal]]
  }

}
