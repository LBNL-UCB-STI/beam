package beam.router.skim

import beam.agentsim.infrastructure.taz.TAZ
import beam.router.skim.TAZSkimmer.{TAZSkimmerInternal, TAZSkimmerKey}
import beam.sim.BeamScenario
import beam.sim.config.BeamConfig
import beam.utils.DateUtils
import org.matsim.api.core.v01.Id

case class TAZSkims(beamConfig: BeamConfig, beamScenario: BeamScenario) extends AbstractSkimmerReadOnly {
  import DateUtils._
  val timeBin: Int = beamConfig.beam.router.skim.taz_skimmer.timeBin

  def getLatestSkim(actor: String, key: String): Map[TAZSkimmerKey, TAZSkimmerInternal] = {
    pastSkims.headOption
      .map(
        _.filter(y => y._1.asInstanceOf[TAZSkimmerKey].actor == actor & y._1.asInstanceOf[TAZSkimmerKey].key == key)
          .map(x => x._1.asInstanceOf[TAZSkimmerKey] -> x._2.asInstanceOf[TAZSkimmerInternal])
      )
      .get
  }

  def getLatestSkim(
    time: Int,
    taz: Id[TAZ],
    hex: String,
    actor: String,
    key: String
  ): Option[TAZSkimmerInternal] = {
    pastSkims.headOption
      .flatMap(_.get(TAZSkimmerKey(toTimeBin(time, timeBin), taz, hex, actor, key)))
      .asInstanceOf[Option[TAZSkimmerInternal]]
  }

  def getLatestSkim(
    time: Int,
    hex: String,
    actor: String,
    key: String
  ): Option[TAZSkimmerInternal] = {
    getLatestSkim(time, beamScenario.h3taz.getTAZ(hex), hex, actor, key)
  }

  def getLatestSkimByTAZ(
    time: Int,
    taz: Id[TAZ],
    actor: String,
    key: String
  ): Option[TAZSkimmerInternal] = {
    beamScenario.h3taz
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
      .get(TAZSkimmerKey(toTimeBin(time, timeBin), taz, hex, actor, key))
      .asInstanceOf[Option[TAZSkimmerInternal]]
  }

}
