package beam.router.readonlyskim

import beam.agentsim.infrastructure.taz.{H3TAZ, TAZ}
import beam.router.skim.Skimmer
import beam.router.skim.Skimmer.{SkimmerInternal, SkimmerKey}
import beam.sim.BeamServices
import beam.sim.vehiclesharing.VehicleManager
import org.matsim.api.core.v01.{Coord, Id}

class ReadOnlySkim(beamServices: BeamServices, h3taz: H3TAZ) extends Skimmer(beamServices, h3taz) {

  def getLatestSkim(
    timeBin: Int,
    idTaz: Id[TAZ],
    hexIndex: String,
    idVehMng: Id[VehicleManager],
    valLabel: String
  ): Option[SkimmerInternal] = {
    this.pastSkims.headOption
      .flatMap(_.get(SkimmerKey(timeBin, idTaz, hexIndex, idVehMng, valLabel)))
      .asInstanceOf[Option[SkimmerInternal]]
  }

  def getAggregatedSkim(
    timeBin: Int,
    idTaz: Id[TAZ],
    hexIndex: String,
    idVehMng: Id[VehicleManager],
    valLabel: String
  ): Option[SkimmerInternal] = {
    this.aggregatedSkim
      .get(SkimmerKey(timeBin, idTaz, hexIndex, idVehMng, valLabel))
      .asInstanceOf[Option[SkimmerInternal]]
  }

}
