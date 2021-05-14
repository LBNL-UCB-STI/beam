package beam.router.skim.readonly

import beam.agentsim.infrastructure.taz.{H3TAZ, TAZ, TAZTreeMap}
import beam.router.skim.core.{AbstractSkimmerInternal, AbstractSkimmerKey, AbstractSkimmerReadOnly}
import beam.router.skim.core.TAZSkimmer.{TAZSkimmerInternal, TAZSkimmerKey}
import beam.sim.BeamScenario
import org.matsim.api.core.v01.Id

case class TAZSkims(beamScenario: BeamScenario) extends AbstractSkimmerReadOnly {

  def getCurrentSkim(
    time: Int,
    actor: String,
    tazMaybe: Option[Id[TAZ]],
    keyMaybe: Option[String],
    hexMaybe: Option[String]
  ): Option[TAZSkimmerInternal] = {
    if (currentSkim.isEmpty) None
    else
      getSkim(currentSkim, time, actor, tazMaybe, keyMaybe, hexMaybe)
  }

  def getPreviousIterationSkim(
    time: Int,
    actor: String,
    tazMaybe: Option[Id[TAZ]],
    keyMaybe: Option[String],
    hexMaybe: Option[String]
  ): Option[TAZSkimmerInternal] = {
    if (pastSkims.isEmpty) None
    else
      getSkim(pastSkims(currentIteration - 1), time, actor, tazMaybe, keyMaybe, hexMaybe)
  }

  private def getSkim(
    theSkim: scala.collection.Map[AbstractSkimmerKey, AbstractSkimmerInternal],
    time: Int,
    actor: String,
    tazMaybe: Option[Id[TAZ]],
    keyMaybe: Option[String],
    hexMaybe: Option[String]
  ): Option[TAZSkimmerInternal] = {
    val geoHierarchy = beamScenario.beamConfig.beam.router.skim.taz_skimmer.geoHierarchy
    if (tazMaybe.isEmpty || keyMaybe.isEmpty || (hexMaybe.isEmpty && geoHierarchy == "H3")) {
      aggregateSkims(
        theSkim
          .filter { s =>
            val s2 = s._1.asInstanceOf[TAZSkimmerKey]
            tazMaybe.forall(_ == s2.taz) && s2.time == time && s2.actor == actor && keyMaybe
              .forall(_ == s2.key) && hexMaybe
              .forall(_ == s2.hex)
          }
          .map(_._2.asInstanceOf[TAZSkimmerInternal])
      )
    } else
      geoHierarchy match {
        case "TAZ" =>
          theSkim
            .get(TAZSkimmerKey(time, tazMaybe.get, H3TAZ.emptyH3, actor, keyMaybe.get))
            .asInstanceOf[Option[TAZSkimmerInternal]]

        case "H3" =>
          theSkim
            .get(TAZSkimmerKey(time, tazMaybe.get, hexMaybe.get, actor, keyMaybe.get))
            .asInstanceOf[Option[TAZSkimmerInternal]]
        case _ =>
          theSkim
            .get(TAZSkimmerKey(time, TAZTreeMap.emptyTAZId, H3TAZ.emptyH3, actor, keyMaybe.get))
            .asInstanceOf[Option[TAZSkimmerInternal]]
      }

  }

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
                    value = (accSkim.value * accSkim.observations + skim.value * skim.observations) / (accSkim.observations + skim.observations),
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
