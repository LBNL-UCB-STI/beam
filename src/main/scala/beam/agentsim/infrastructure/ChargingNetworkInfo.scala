package beam.agentsim.infrastructure

import beam.agentsim.agents.vehicles.VehicleManager
import beam.agentsim.infrastructure.ChargingNetworkManager.ChargingZone
import beam.agentsim.infrastructure.taz.TAZ
import beam.sim.BeamServices
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.Id
import org.matsim.core.utils.collections.QuadTree

import scala.util.Random

case class ChargingNetworkInfo(
  beamServices: BeamServices,
  envelopeInUTM: Envelope,
  vehicleManagers: Map[Id[VehicleManager], VehicleManager]
) {
  import ChargingNetworkInfo._
  private[infrastructure] val chargingZoneList: QuadTree[ChargingZone] = loadChargingZones(beamServices, envelopeInUTM)
}

object ChargingNetworkInfo {

  /**
    * load parking stalls with charging point
    * @param beamServices BeamServices
    * @return QuadTree of ChargingZone
    */
  private def loadChargingZones(beamServices: BeamServices, envelopeInUTM: Envelope): QuadTree[ChargingZone] = {
    import beamServices._
    val parkingFilePath: String = beamConfig.beam.agentsim.taz.parkingFilePath
    val parkingStallCountScalingFactor = beamConfig.beam.agentsim.taz.parkingStallCountScalingFactor
    val parkingCostScalingFactor = beamConfig.beam.agentsim.taz.parkingCostScalingFactor
    val random = new Random(beamConfig.matsim.modules.global.randomSeed)
    val (zones, _) = ZonalParkingManager.loadParkingZones[TAZ](
      parkingFilePath,
      beamScenario.tazTreeMap.tazQuadTree,
      parkingStallCountScalingFactor,
      parkingCostScalingFactor,
      random
    )
    val zonesWithCharger =
      zones.filter(_.chargingPointType.isDefined).map { z =>
        (z, beamScenario.tazTreeMap.getTAZ(z.geoId).get)
      }
    val coordinates = zonesWithCharger.map(_._2.coord)
    val xs = coordinates.map(_.getX)
    val ys = coordinates.map(_.getY)
//    val envelopeInUTM = geo.wgs2Utm(beamScenario.transportNetwork.streetLayer.envelope)
    envelopeInUTM.expandBy(beamConfig.beam.spatial.boundingBoxBuffer)
    envelopeInUTM.expandToInclude(xs.min, ys.min)
    envelopeInUTM.expandToInclude(xs.max, ys.max)

    val stationsQuadTree = new QuadTree[ChargingZone](
      envelopeInUTM.getMinX,
      envelopeInUTM.getMinY,
      envelopeInUTM.getMaxX,
      envelopeInUTM.getMaxY
    )
    zonesWithCharger.foreach {
      case (zone, taz) =>
        stationsQuadTree.put(
          taz.coord.getX,
          taz.coord.getY,
          ChargingZone(
            zone.geoId,
            zone.parkingType,
            zone.maxStalls,
            zone.chargingPointType.get,
            zone.pricingModel.get,
            zone.vehicleManagerId
          )
        )
    }
    stationsQuadTree
  }
}
