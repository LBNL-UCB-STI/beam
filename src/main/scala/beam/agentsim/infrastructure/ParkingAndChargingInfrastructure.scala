package beam.agentsim.infrastructure

import beam.agentsim.infrastructure.HierarchicalParkingManager.convertToTazParkingZones
import beam.agentsim.infrastructure.parking.ParkingZoneFileUtils.ParkingLoadingAccumulator
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.ZoneSearchTree
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.TAZ
import beam.sim.BeamServices
import beam.sim.vehiclesharing.Fleets
import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.matsim.core.utils.collections.QuadTree

import scala.collection.JavaConverters._
import scala.language.existentials
import scala.util.{Failure, Random, Success, Try}

case class ParkingAndChargingInfrastructure(beamServices: BeamServices, envelopeInUTM: Envelope) {
  import ParkingAndChargingInfrastructure._
  import beamServices._

  // RIDE HAIL
  lazy val rideHailParkingNetworkMap: ParkingNetwork[_] =
    beamServices.beamCustomizationAPI.getRideHailDepotParkingManager(beamServices, envelopeInUTM)

  // ALL OTHERS
  private val mainParkingFile: String = beamConfig.beam.agentsim.taz.parkingFilePath
  // ADD HERE ALL PARKING FILES THAT BELONGS TO VEHICLE MANAGERS
  private val vehicleManagersParkingFiles: IndexedSeq[String] = {
    val sharedFleetsParkingFiles =
      beamConfig.beam.agentsim.agents.vehicles.sharedFleets.map(Fleets.lookup).map(_.parkingFilePath)
    val freightParkingFile = beamConfig.beam.agentsim.agents.freight.carrierParkingFilePath.toList
    (sharedFleetsParkingFiles ++ freightParkingFile).toIndexedSeq
  }

  val ((chargingNetworks, chargingFunctions), parkingNetwork) =
    buildChargingAndParkingNetwork(beamServices, envelopeInUTM, mainParkingFile, vehicleManagersParkingFiles)
}

object ParkingAndChargingInfrastructure extends LazyLogging {

  private def buildChargingAndParkingNetwork(
    beamServices: BeamServices,
    envelopeInUTM: Envelope,
    mainParkingFile: String,
    vehicleManagersParkingFiles: IndexedSeq[String]
  ): ((Vector[ChargingNetwork[_]], ChargingFunctions[_]), ParkingNetwork[_]) = {
    import beamServices._
    logger.info(s"Starting parking manager: ${beamConfig.beam.agentsim.taz.parkingManager.name}")
    beamConfig.beam.agentsim.taz.parkingManager.name match {
      case "DEFAULT" =>
        beamConfig.beam.agentsim.taz.parkingManager.level.toLowerCase match {
          case "taz" =>
            val (stalls, searchTree) =
              loadParkingZones[TAZ](
                mainParkingFile,
                vehicleManagersParkingFiles,
                beamScenario.tazTreeMap.tazQuadTree,
                beamConfig.beam.agentsim.taz.parkingStallCountScalingFactor,
                beamConfig.beam.agentsim.taz.parkingCostScalingFactor,
                beamConfig.matsim.modules.global.randomSeed
              )
            (
              ChargingNetwork.init(
                stalls,
                beamScenario.tazTreeMap.tazQuadTree,
                beamScenario.tazTreeMap.idToTAZMapping,
                identity[TAZ](_),
                envelopeInUTM,
                beamConfig,
                geo
              ),
              ZonalParkingManager.init(
                beamConfig,
                beamScenario.tazTreeMap.tazQuadTree,
                beamScenario.tazTreeMap.idToTAZMapping,
                identity[TAZ](_),
                geo,
                envelopeInUTM,
                stalls,
                searchTree,
                beamConfig.matsim.modules.global.randomSeed
              )
            )
          case "link" =>
            val (stalls, searchTree) =
              loadParkingZones[Link](
                mainParkingFile,
                vehicleManagersParkingFiles,
                beamScenario.linkQuadTree,
                beamConfig.beam.agentsim.taz.parkingStallCountScalingFactor,
                beamConfig.beam.agentsim.taz.parkingCostScalingFactor,
                beamConfig.matsim.modules.global.randomSeed
              )
            (
              ChargingNetwork.init(
                stalls,
                beamScenario.linkQuadTree,
                beamScenario.linkIdMapping,
                beamScenario.linkToTAZMapping,
                envelopeInUTM,
                beamConfig,
                geo
              ),
              ZonalParkingManager.init(
                beamScenario.beamConfig,
                beamScenario.linkQuadTree,
                beamScenario.linkIdMapping,
                beamScenario.linkToTAZMapping,
                geo,
                envelopeInUTM,
                stalls,
                searchTree,
                beamConfig.matsim.modules.global.randomSeed
              )
            )
          case _ =>
            throw new IllegalArgumentException(
              s"Unsupported parking level type ${beamConfig.beam.agentsim.taz.parkingManager.level}, only TAZ | Link are supported"
            )
        }
      case "HIERARCHICAL" =>
        val (stalls, _) =
          loadParkingZones[Link](
            mainParkingFile,
            vehicleManagersParkingFiles,
            beamScenario.linkQuadTree,
            beamConfig.beam.agentsim.taz.parkingStallCountScalingFactor,
            beamConfig.beam.agentsim.taz.parkingCostScalingFactor,
            beamConfig.matsim.modules.global.randomSeed
          )
        val (tazParkingZones, _) = convertToTazParkingZones(stalls, beamScenario.linkToTAZMapping.map {
          case (link, taz) => link.getId -> taz.tazId
        })
        (
          ChargingNetwork.init[TAZ](
            tazParkingZones,
            beamScenario.tazTreeMap.tazQuadTree,
            beamScenario.tazTreeMap.idToTAZMapping,
            identity[TAZ](_),
            envelopeInUTM,
            beamConfig,
            geo
          ),
          HierarchicalParkingManager
            .init(beamConfig, beamScenario.tazTreeMap, beamScenario.linkToTAZMapping, geo, envelopeInUTM, stalls)
        )
      case "PARALLEL" =>
        val (stalls, searchTree) =
          loadParkingZones[TAZ](
            mainParkingFile,
            vehicleManagersParkingFiles,
            beamScenario.tazTreeMap.tazQuadTree,
            beamConfig.beam.agentsim.taz.parkingStallCountScalingFactor,
            beamConfig.beam.agentsim.taz.parkingCostScalingFactor,
            beamConfig.matsim.modules.global.randomSeed
          )
        (
          ChargingNetwork.init(
            stalls,
            beamScenario.tazTreeMap.tazQuadTree,
            beamScenario.tazTreeMap.idToTAZMapping,
            identity[TAZ](_),
            envelopeInUTM,
            beamConfig,
            geo
          ),
          ParallelParkingManager.init(beamConfig, beamScenario.tazTreeMap, stalls, searchTree, geo, envelopeInUTM)
        )

      case unknown @ _ => throw new IllegalArgumentException(s"Unknown parking manager type: $unknown")
    }
  }

  def loadParkingZones[GEO: GeoLevel](
    parkingFilePath: String,
    depotFilePaths: IndexedSeq[String],
    geoQuadTree: QuadTree[GEO],
    parkingStallCountScalingFactor: Double,
    parkingCostScalingFactor: Double,
    seed: Long
  ): (Map[Id[ParkingZoneId], ParkingZone[GEO]], ZoneSearchTree[GEO]) = {
    val random = new Random(seed)
    val initialAccumulator: ParkingLoadingAccumulator[GEO] = if (parkingFilePath.isEmpty) {
      ParkingZoneFileUtils.generateDefaultParkingAccumulatorFromGeoObjects(geoQuadTree.values().asScala, random)
    } else {
      Try {
        ParkingZoneFileUtils.fromFileToAccumulator(
          parkingFilePath,
          random,
          parkingStallCountScalingFactor,
          parkingCostScalingFactor
        )
      } match {
        case Success(accumulator) => accumulator
        case Failure(e) =>
          logger.error(s"unable to read contents of provided parking file $parkingFilePath", e)
          ParkingZoneFileUtils.generateDefaultParkingAccumulatorFromGeoObjects(
            geoQuadTree.values().asScala,
            random
          )
      }
    }
    val parkingLoadingAccumulator = depotFilePaths.foldLeft(initialAccumulator) {
      case (acc, filePath) =>
        filePath.trim match {
          case "" => acc
          case depotParkingFilePath @ _ =>
            Try {
              ParkingZoneFileUtils.fromFileToAccumulator(
                depotParkingFilePath,
                random,
                parkingStallCountScalingFactor,
                parkingCostScalingFactor,
                acc
              )
            } match {
              case Success(accumulator) => accumulator
              case Failure(e) =>
                logger.error(s"unable to read contents of provided parking file $depotParkingFilePath", e)
                acc
            }
        }
    }
    (parkingLoadingAccumulator.zones.toMap, parkingLoadingAccumulator.tree)
  }

}
