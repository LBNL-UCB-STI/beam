package beam.agentsim.infrastructure

import beam.agentsim.agents.vehicles.VehicleManager
import beam.agentsim.infrastructure.ChargingNetworkManager.ChargingZone
import beam.agentsim.infrastructure.parking.ParkingNetwork
import beam.agentsim.infrastructure.taz.TAZ
import beam.sim.BeamServices
import beam.sim.vehiclesharing.Fleets
import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.matsim.core.utils.collections.QuadTree

case class ParkingAndChargingInfrastructure(beamServices: BeamServices, envelopeInUTM: Envelope) {
  import ParkingAndChargingInfrastructure._
  import beamServices._

  // RIDEHAIL
  lazy val rideHailParkingNetworkMap: ParkingNetwork[_] =
    beamServices.beamCustomizationAPI.getRideHailDepotParkingManager(beamServices, envelopeInUTM)

  // ALL OTHERS
  private val mainParkingFilePath: String = beamConfig.beam.agentsim.taz.parkingFilePath
  // ADD HERE ALL PARKING FILES THAT BELONGS TO VEHICLE MANAGERS
  private val vehicleManagersParkingFilePaths: IndexedSeq[String] = {
    val sharedFleetsParkingFilePaths =
      beamConfig.beam.agentsim.agents.vehicles.sharedFleets.map(Fleets.lookup).map(_.parkingFilePath)
    val freightParkingFilePath = beamConfig.beam.agentsim.agents.freight.carrierParkingFilePath.toList
    (sharedFleetsParkingFilePaths ++ freightParkingFilePath).toIndexedSeq
  }

  lazy val parkingNetwork: ParkingNetwork[_] =
    buildParkingNetwork(beamServices, envelopeInUTM, mainParkingFilePath, vehicleManagersParkingFilePaths)

  lazy val chargingNetwork: Map[Option[Id[VehicleManager]], QuadTree[ChargingZone]] =
    buildingChargingNetwork(beamServices, envelopeInUTM, parkingNetwork)

}

object ParkingAndChargingInfrastructure extends LazyLogging {
  private def buildParkingNetwork(
    beamServices: BeamServices,
    envelopeInUTM: Envelope,
    mainParkingFilePath: String,
    vehicleManagersParkingFilePaths: IndexedSeq[String]
  ): ParkingNetwork[_] = {
    import beamServices._
    logger.info(s"Starting parking manager: ${beamConfig.beam.agentsim.taz.parkingManager.name}")
    beamConfig.beam.agentsim.taz.parkingManager.name match {
      case "DEFAULT" =>
        val geoLevel = beamConfig.beam.agentsim.taz.parkingManager.level
        geoLevel.toLowerCase match {
          case "taz" =>
            ZonalParkingManager.init(
              beamScenario.beamConfig,
              beamScenario.tazTreeMap.tazQuadTree,
              beamScenario.tazTreeMap.idToTAZMapping,
              identity[TAZ](_),
              geo,
              beamRouter,
              envelopeInUTM,
              mainParkingFilePath,
              vehicleManagersParkingFilePaths
            )
          case "link" =>
            ZonalParkingManager.init(
              beamScenario.beamConfig,
              beamScenario.linkQuadTree,
              beamScenario.linkIdMapping,
              beamScenario.linkToTAZMapping,
              geo,
              beamRouter,
              envelopeInUTM,
              mainParkingFilePath,
              vehicleManagersParkingFilePaths
            )
          case _ =>
            throw new IllegalArgumentException(
              s"Unsupported parking level type $geoLevel, only TAZ | Link are supported"
            )
        }
      case "HIERARCHICAL" =>
        HierarchicalParkingManager
          .init(
            beamConfig,
            beamScenario.tazTreeMap,
            beamScenario.linkQuadTree,
            beamScenario.linkToTAZMapping,
            geo,
            envelopeInUTM,
            mainParkingFilePath,
            vehicleManagersParkingFilePaths
          )
      case "PARALLEL" =>
        ParallelParkingManager.init(
          beamScenario.beamConfig,
          beamScenario.tazTreeMap,
          geo,
          envelopeInUTM,
          mainParkingFilePath,
          vehicleManagersParkingFilePaths
        )
      case unknown => throw new IllegalArgumentException(s"Unknown parking manager type: $unknown")
    }
  }

  /**
    * load parking stalls with charging point
    * @param beamServices BeamServices
    * @return QuadTree of ChargingZone
    */
  private def buildingChargingNetwork(
    beamServices: BeamServices,
    envelopeInUTM: Envelope,
    parkingNetwork: ParkingNetwork[_]
  ) = {
    import beamServices._

    import scala.language.existentials
    val zones = parkingNetwork.getParkingZones()
    val zonesWithCharger =
      zones.filter(_.chargingPointType.isDefined).map { z =>
        val geoLevel = beamConfig.beam.agentsim.taz.parkingManager.level
        val coord = geoLevel.toLowerCase match {
          case "taz" =>
            beamScenario.tazTreeMap.getTAZ(z.geoId.asInstanceOf[Id[TAZ]]).get.coord
          case "link" =>
            beamScenario.network.getLinks.get(z.geoId.asInstanceOf[Id[Link]]).getCoord
          case _ =>
            throw new IllegalArgumentException(
              s"Unsupported parking level type $geoLevel, only TAZ | Link are supported"
            )
        }
        (z, coord)
      }
    val coordinates = zonesWithCharger.map(_._2)
    val xs = coordinates.map(_.getX)
    val ys = coordinates.map(_.getY)
    envelopeInUTM.expandBy(beamConfig.beam.spatial.boundingBoxBuffer)
    envelopeInUTM.expandToInclude(xs.min, ys.min)
    envelopeInUTM.expandToInclude(xs.max, ys.max)
    zonesWithCharger
      .groupBy { case (zone, _) => zone.vehicleManager }
      .mapValues { zones =>
        val stationsQuadTree = new QuadTree[ChargingZone](
          envelopeInUTM.getMinX,
          envelopeInUTM.getMinY,
          envelopeInUTM.getMaxX,
          envelopeInUTM.getMaxY
        )
        zones.foreach {
          case (zone, coord) =>
            stationsQuadTree.put(
              coord.getX,
              coord.getY,
              ChargingZone(
                zone.geoId,
                beamScenario.tazTreeMap.getTAZ(coord).tazId,
                zone.parkingType,
                zone.maxStalls,
                zone.chargingPointType.get,
                zone.pricingModel.get,
                zone.vehicleManager
              )
            )
        }
        stationsQuadTree
      }
  }
}
