package beam.router.skim.readonly

import beam.agentsim.infrastructure.taz.TAZ
import beam.router.skim.core.AbstractSkimmerReadOnly
import beam.router.skim.core.TAZSkimmer.{TAZSkimmerInternal, TAZSkimmerKey}
import beam.sim.BeamScenario
import org.matsim.api.core.v01.Id

case class TAZSkims(beamScenario: BeamScenario) extends AbstractSkimmerReadOnly {

  def isPartialSkimEmpty: Boolean = isEmpty

  def getPartialSkim(time: Int, taz: Id[TAZ], hex: String, actor: String, key: String): Option[TAZSkimmerInternal] =
    getCurrentSkimValue(TAZSkimmerKey(time, taz, hex, actor, key)).asInstanceOf[Option[TAZSkimmerInternal]]

  def getPartialSkim(time: Int, hex: String, actor: String, key: String): Option[TAZSkimmerInternal] =
    getPartialSkim(time, beamScenario.h3taz.getTAZ(hex), hex, actor, key)

  def getPartialSkim(time: Int, taz: Id[TAZ], actor: String, key: String): Option[TAZSkimmerInternal] =
    aggregateSkims(beamScenario.h3taz.getIndices(taz).flatMap(getPartialSkim(time, taz, _, actor, key)))

  def isLatestSkimEmpty: Boolean = pastSkims.isEmpty

  def getLatestSkim(time: Int, taz: Id[TAZ], hex: String, actor: String, key: String): Option[TAZSkimmerInternal] =
    pastSkims
      .get(currentIteration - 1)
      .flatMap(_.get(TAZSkimmerKey(time, taz, hex, actor, key)))
      .asInstanceOf[Option[TAZSkimmerInternal]]

  def getLatestSkim(time: Int, hex: String, actor: String, key: String): Option[TAZSkimmerInternal] =
    getLatestSkim(time, beamScenario.h3taz.getTAZ(hex), hex, actor, key)

  def getLatestSkim(time: Int, taz: Id[TAZ], actor: String, key: String): Option[TAZSkimmerInternal] =
    aggregateSkims(beamScenario.h3taz.getIndices(taz).flatMap(getLatestSkim(time, taz, _, actor, key)))

  def getAggregatedSkim(time: Int, taz: Id[TAZ], hex: String, actor: String, key: String): Option[TAZSkimmerInternal] =
    aggregatedFromPastSkims.get(TAZSkimmerKey(time, taz, hex, actor, key)).asInstanceOf[Option[TAZSkimmerInternal]]

  private def aggregateSkims(skims: Iterable[TAZSkimmerInternal]): Option[TAZSkimmerInternal] = {
    try {
      skims
        .toSet[TAZSkimmerInternal]
        .foldLeft[Option[TAZSkimmerInternal]](None) {
          case (accSkimMaybe, skim: TAZSkimmerInternal) =>
            accSkimMaybe match {
              case Some(accSkim) =>
                Some(
                  TAZSkimmerInternal(
                    value = (accSkim.value * accSkim.observations + skim.value + skim.observations) / (accSkim.observations + skim.observations),
                    observations = accSkim.observations + skim.observations,
                    iterations = accSkim.iterations
                  )
                )
              case _ => Some(skim)
            }
        }
    } catch {
      case e: ClassCastException =>
        logger.error(s"$e")
        None
    }
  }

}
