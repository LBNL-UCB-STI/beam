package beam.agentsim.infrastructure

import beam.agentsim.agents.vehicles.{VehicleManager, VehicleManagerType}
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

  private[infrastructure] val chargingNetworkMap: Map[Id[VehicleManager], ChargingNetwork] = vehicleManagers.flatMap {
    case (vehicleManagerId, VehicleManager(_, vehicleManagerType)) =>
      vehicleManagerType match {
        case VehicleManagerType.Ridehail =>
          None
        case VehicleManagerType.Cars =>
          Some(
            vehicleManagerId -> new ChargingNetwork(
              vehicleManagerId,
              loadPublicChargingZones(beamServices, envelopeInUTM)
            )
          )
        case _ =>
          None
      }
  }
}

object ChargingNetworkInfo {

  /**
    * load parking stalls with charging point
    * @param beamServices BeamServices
    * @return QuadTree of ChargingZone
    */
  private def loadPublicChargingZones(beamServices: BeamServices, envelopeInUTM: Envelope) = {
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
    val publicZonesWithChargers =
      zones.filter(_.chargingPointType.isDefined).map(z => (z, beamScenario.tazTreeMap.getTAZ(z.geoId).get))
    val coordinates = publicZonesWithChargers.map(_._2.coord)
    val xs = coordinates.map(_.getX)
    val ys = coordinates.map(_.getY)
    envelopeInUTM.expandBy(beamServices.beamConfig.beam.spatial.boundingBoxBuffer)
    envelopeInUTM.expandToInclude(xs.min, ys.min)
    envelopeInUTM.expandToInclude(xs.max, ys.max)
    val chargingZonesQuadTree = new QuadTree[ChargingZone](
      envelopeInUTM.getMinX,
      envelopeInUTM.getMinY,
      envelopeInUTM.getMaxX,
      envelopeInUTM.getMaxY
    )
    publicZonesWithChargers.foreach {
      case (zone, taz) =>
        chargingZonesQuadTree.put(
          taz.coord.getX,
          taz.coord.getY,
          ChargingZone(
            zone.parkingZoneId,
            zone.geoId,
            zone.parkingType,
            zone.maxStalls,
            zone.chargingPointType.get,
            zone.pricingModel.get,
            zone.vehicleManagerId
          )
        )
    }
    chargingZonesQuadTree
  }
}
