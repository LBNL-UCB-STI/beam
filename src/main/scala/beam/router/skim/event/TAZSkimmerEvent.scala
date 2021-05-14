package beam.router.skim.event

import beam.router.skim.core.TAZSkimmer.{TAZSkimmerInternal, TAZSkimmerKey}
import beam.router.skim.core.{AbstractSkimmerEvent, AbstractSkimmerInternal, AbstractSkimmerKey}
import beam.sim.BeamServices
import org.matsim.api.core.v01.{Coord, Id}
import beam.agentsim.infrastructure.taz._

case class TAZSkimmerEvent(
  time: Int,
  coord: Coord,
  key: String,
  value: Double,
  beamServices: BeamServices,
  actor: String = "default"
) extends AbstractSkimmerEvent(time) {
  override protected val skimName: String = beamServices.beamConfig.beam.router.skim.taz_skimmer.name

  private val (tazIndexMaybe, hexIndexMaybe) = beamServices.beamConfig.beam.router.skim.taz_skimmer.geoHierarchy match {
    case "H3" =>
      val hexIndex: String = beamServices.beamScenario.h3taz.getIndex(coord)
      val tazIndex: Id[TAZ] = beamServices.beamScenario.tazTreeMap.getTAZ(coord).tazId
      (Some(tazIndex), Some(hexIndex))
    case "TAZ" =>
      val tazIndex: Id[TAZ] = beamServices.beamScenario.tazTreeMap.getTAZ(coord).tazId
      (Some(tazIndex), None)
    case _ =>
      (Some(TAZTreeMap.emptyTAZId), None)
  }

  def getTazIndex: Option[Id[TAZ]] = tazIndexMaybe
  def getHexIndex: Option[String] = hexIndexMaybe

  override def getKey: AbstractSkimmerKey =
    TAZSkimmerKey(
      time,
      tazIndexMaybe.getOrElse(TAZTreeMap.emptyTAZId),
      hexIndexMaybe.getOrElse(H3TAZ.emptyH3),
      actor,
      key
    )
  override def getSkimmerInternal: AbstractSkimmerInternal =
    TAZSkimmerInternal(value, 1, beamServices.matsimServices.getIterationNumber + 1)
}
