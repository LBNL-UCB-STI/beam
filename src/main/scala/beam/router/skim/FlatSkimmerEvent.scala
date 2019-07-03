package beam.router.skim

import beam.agentsim.infrastructure.taz.{H3TAZ, TAZ}
import beam.sim.vehiclesharing.VehicleManager
import org.matsim.api.core.v01.{Coord, Id}
import beam.sim.{BeamObserverData, BeamObserverEvent, BeamObserverKey}

class FlatSkimmerEvent(time: Double, bin: Int, coord: Coord, vehMng: Id[VehicleManager], label: String, value: Double)
    extends BeamObserverEvent {
  override def getEventType: String = "FlatSkimmerEvent"
  override def getKey: BeamObserverKey = {
    var hexIndex = "NA"
    var idTaz = H3TAZ.emptyTAZId
    FlatSkimmer.h3taz match {
      case Some(h3taz) =>
        h3taz.getHex(coord.getX, coord.getY) match {
          case Some(hex) =>
            hexIndex = hex
            idTaz = h3taz.getTAZ(hex)
          case _ =>
        }
      case _ =>
    }
    FlatSkimmerKey(bin, idTaz, hexIndex, vehMng, label)
  }
  override def getData: BeamObserverData = FlatSkimmerData(value)
}
