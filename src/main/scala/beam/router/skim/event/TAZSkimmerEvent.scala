package beam.router.skim.event

import beam.router.skim.core.TAZSkimmer.{TAZSkimmerInternal, TAZSkimmerKey}
import beam.router.skim.core.{AbstractSkimmerEvent, AbstractSkimmerInternal, AbstractSkimmerKey}
import beam.sim.BeamServices
import org.matsim.api.core.v01.Coord

case class TAZSkimmerEvent(
  time: Int,
  coord: Coord,
  key: String,
  value: Double,
  beamServices: BeamServices,
  actor: String = "default",
  geoIdMaybe: Option[String] = None
) extends AbstractSkimmerEvent(time) {
  override protected val skimName: String = beamServices.beamConfig.beam.router.skim.taz_skimmer.name

  private val idTaz = geoIdMaybe match {
    case Some(geoId) => geoId
    case _           => beamServices.beamScenario.tazTreeMap.getTAZ(coord).tazId.toString
  }
  override def getKey: AbstractSkimmerKey = TAZSkimmerKey(time, idTaz, actor, key)

  override def getSkimmerInternal: AbstractSkimmerInternal =
    TAZSkimmerInternal(value, 1, beamServices.matsimServices.getIterationNumber + 1)
}
