package beam.router.skim.event

import beam.router.skim.core.TAZSkimmer.{TAZSkimmerInternal, TAZSkimmerKey}
import beam.router.skim.core.{AbstractSkimmerEvent, AbstractSkimmerInternal, AbstractSkimmerKey}
import beam.sim.BeamServices
import org.matsim.api.core.v01.Id
import beam.agentsim.infrastructure.taz._

case class TAZSkimmerEvent(
  time: Int,
  tazId: Id[TAZ],
  key: String,
  value: Double,
  beamServices: BeamServices,
  actor: String = "default"
) extends AbstractSkimmerEvent(time) {
  override protected val skimName: String = beamServices.beamConfig.beam.router.skim.taz_skimmer.name
  override def getKey: AbstractSkimmerKey = TAZSkimmerKey(time, tazId, H3TAZ.emptyH3, actor, key)
  override def getSkimmerInternal: AbstractSkimmerInternal =
    TAZSkimmerInternal(value, 1, beamServices.matsimServices.getIterationNumber + 1)
}
