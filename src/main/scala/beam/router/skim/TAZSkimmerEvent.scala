package beam.router.skim

import beam.router.skim.TAZSkimmer.{TAZSkimmerInternal, TAZSkimmerKey}
import beam.sim.BeamServices
import beam.utils.DateUtils
import org.matsim.api.core.v01.Coord

case class TAZSkimmerEvent(
  time: Int,
  coord: Coord,
  key: String,
  value: Double,
  beamServices: BeamServices,
  actor: String = "default"
) extends AbstractSkimmerEvent(time) {
  private val hexIndex = beamServices.beamScenario.h3taz.getIndex(coord)
  private val idTaz = beamServices.beamScenario.h3taz.getTAZ(hexIndex)
  override protected def skimName: String = beamServices.beamConfig.beam.router.skim.taz_skimmer.name
  override def getKey: AbstractSkimmerKey =
    TAZSkimmerKey(
      DateUtils.toTimeBin(time, beamServices.beamConfig.beam.router.skim.taz_skimmer.timeBin),
      idTaz,
      hexIndex,
      actor,
      key
    )
  override def getSkimmerInternal: AbstractSkimmerInternal =
    TAZSkimmerInternal(value, 1, beamServices.matsimServices.getIterationNumber + 1)
}
