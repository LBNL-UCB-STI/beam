package beam.router.skim

import beam.router.skim.CountSkimmer.{CountSkimmerInternal, CountSkimmerKey}
import beam.sim.BeamServices
import org.matsim.api.core.v01.Coord

case class CountSkimmerEvent(
  eventTime: Double,
  beamServices: BeamServices,
  time: Int,
  coord: Coord,
  groupId: String = "default",
  label: String,
  sumValue: Double = 0,
  meanValue: Double = 0,
  numObservations: Int = 1
) extends AbstractSkimmerEvent(eventTime, beamServices) {
  override protected val skimName: String = beamServices.beamConfig.beam.router.skim.count_skimmer.name
  private val hexIndex = beamServices.beamScenario.h3taz.getHRHex(coord.getX, coord.getY)
  private val idTaz = beamServices.beamScenario.h3taz.getTAZ(hexIndex)
  override def getKey: AbstractSkimmerKey = CountSkimmerKey(time, idTaz, hexIndex, groupId, label)
  override def getSkimmerInternal: AbstractSkimmerInternal =
    CountSkimmerInternal(sumValue, meanValue, numObservations)
}
