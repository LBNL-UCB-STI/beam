package beam.router.skim

import beam.agentsim.infrastructure.taz.TAZ
import beam.router.skim.CountSkimmer.{CountSkimmerInternal, CountSkimmerKey}
import beam.sim.BeamServices
import beam.sim.vehiclesharing.VehicleManager
import org.matsim.api.core.v01.Id

case class CountSkims(beamServices: BeamServices) extends AbstractSkimmerReadOnly(beamServices) {

  def getLatestSkim(
    timeBin: Int,
    idTaz: Id[TAZ],
    hexIndex: String,
    idVehMng: Id[VehicleManager],
    variableName: String
  ): Option[CountSkimmerInternal] = {
    pastSkims.headOption
      .flatMap(_.get(CountSkimmerKey(timeBin, idTaz, hexIndex, idVehMng, variableName)))
      .asInstanceOf[Option[CountSkimmerInternal]]
  }

  def getLatestSkim(
    timeBin: Int,
    hexIndex: String,
    idVehMng: Id[VehicleManager],
    variableName: String
  ): Option[CountSkimmerInternal] = {
    getLatestSkim(timeBin, beamServices.beamScenario.h3taz.getTAZ(hexIndex), hexIndex, idVehMng, variableName)
  }

  def getLatestSkimByTAZ(
    timeBin: Int,
    idTaz: Id[TAZ],
    idVehMng: Id[VehicleManager],
    variableName: String
  ): Option[CountSkimmerInternal] = {
    beamServices.beamScenario.h3taz
      .getHRHex(idTaz)
      .flatMap(hexIndex => getLatestSkim(timeBin, idTaz, hexIndex, idVehMng, variableName))
      .foldLeft[Option[CountSkimmerInternal]](None) {
        case (acc, skimInternal) =>
          acc match {
            case Some(skim) => Some(CountSkimmerInternal(skim.count + skimInternal.count))
            case _          => Some(skimInternal)
          }
      }
  }

  def getAggregatedSkim(
    timeBin: Int,
    idTaz: Id[TAZ],
    hexIndex: String,
    idVehMng: Id[VehicleManager],
    variableName: String
  ): Option[CountSkimmerInternal] = {
    aggregatedSkim
      .get(CountSkimmerKey(timeBin, idTaz, hexIndex, idVehMng, variableName))
      .asInstanceOf[Option[CountSkimmerInternal]]
  }

}
