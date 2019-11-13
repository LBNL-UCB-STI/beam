package beam.router.skim

import beam.router.skim.CountSkimmer.{CountSkimmerInternal, CountSkimmerKey}
import beam.sim.BeamServices
import beam.sim.vehiclesharing.VehicleManager
import org.matsim.api.core.v01.{Coord, Id}

case class CountSkimmerEvent(
  eventTime: Double,
  beamServices: BeamServices,
  timeBin: Int,
  coord: Coord,
  idVehMng: Id[VehicleManager],
  variableName: String,
  count: Int = 1
) extends SkimmerEvent(eventTime, beamServices) {
  override def getEventType: String = CountSkimmer.eventType
  private val hexIndex = beamServices.beamScenario.h3taz.getHRHex(coord.getX, coord.getY)
  private val idTaz = beamServices.beamScenario.h3taz.getTAZ(hexIndex)
  override def getKey: AbstractSkimmerKey = CountSkimmerKey(timeBin, idTaz, hexIndex, idVehMng, variableName)
  override def getSkimmerInternal: AbstractSkimmerInternal = CountSkimmerInternal(count)
}
