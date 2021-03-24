package beam.agentsim.infrastructure

import beam.agentsim.agents.vehicles.{VehicleManager, VehicleManagerType}
import beam.agentsim.infrastructure.taz.TAZ
import beam.sim.BeamServices
import beam.sim.vehiclesharing.Fleets
import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.Id

case class ParkingNetworkInfo(
  beamServices: BeamServices,
  envelopeInUTM: Envelope,
  vehicleManagers: Map[Id[VehicleManager], VehicleManager]
) {
  import ParkingNetworkInfo._

  private[infrastructure] val parkingNetworkMap: Map[Id[VehicleManager], ParkingNetwork] = vehicleManagers.flatMap {
    case (vehicleManagerId, VehicleManager(_, vehicleManagerType)) =>
      vehicleManagerType match {
        case VehicleManagerType.Ridehail =>
          Some(
            vehicleManagerId -> beamServices.beamCustomizationAPI
              .getRideHailDepotParkingManager(beamServices, envelopeInUTM, vehicleManagerId)
          )
        case VehicleManagerType.Cars =>
          Some(vehicleManagerId -> buildPublicParkingNetwork(beamServices, envelopeInUTM, vehicleManagers))
        case _ =>
          None
      }
  }

  def getParkingNetwork(managerId: Id[VehicleManager]): ParkingNetwork = parkingNetworkMap(managerId)

  def getPrivateCarsParkingNetwork: ParkingNetwork = parkingNetworkMap(VehicleManager.privateVehicleManager.managerId)

  def getRideHailParking: ParkingNetwork =
    parkingNetworkMap(vehicleManagers.filter(_._2.managerType == VehicleManagerType.Ridehail).head._1)
}

object ParkingNetworkInfo extends LazyLogging {
  private def buildPublicParkingNetwork(
    beamServices: BeamServices,
    envelopeInUTM: Envelope,
    vehicleManagers: Map[Id[VehicleManager], VehicleManager]
  ): ParkingNetwork = {
    import beamServices._
    val managerName = beamConfig.beam.agentsim.taz.parkingManager.name
    val parkingFilePaths = {
      val sharedVehicleFleetTypes = beamConfig.beam.agentsim.agents.vehicles.sharedFleets.map(Fleets.lookup)
      ZonalParkingManager.getDefaultParkingZones(beamConfig) ++ sharedVehicleFleetTypes.map(
        fleetType => fleetType.managerId -> fleetType.parkingFilePath
      )
    }
    logger.info(s"Starting parking manager: $managerName")
    managerName match {
      case "DEFAULT" =>
        val geoLevel = beamConfig.beam.agentsim.taz.parkingManager.level
        geoLevel.toLowerCase match {
          case "taz" =>
            ZonalParkingManager.init(
              beamScenario.beamConfig,
              beamScenario.tazTreeMap.tazQuadTree,
              beamScenario.tazTreeMap.idToTAZMapping,
              identity[TAZ],
              geo,
              beamRouter,
              envelopeInUTM,
              parkingFilePaths,
              vehicleManagers
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
              parkingFilePaths,
              vehicleManagers
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
            parkingFilePaths,
            vehicleManagers
          )
      case "PARALLEL" =>
        ParallelParkingManager.init(
          beamScenario.beamConfig,
          beamScenario.tazTreeMap,
          geo,
          envelopeInUTM,
          parkingFilePaths,
          vehicleManagers
        )
      case unknown @ _ => throw new IllegalArgumentException(s"Unknown parking manager type: $unknown")
    }
  }
}
