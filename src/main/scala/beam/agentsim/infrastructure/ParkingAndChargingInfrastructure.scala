package beam.agentsim.infrastructure

import beam.agentsim.agents.vehicles.VehicleManager
import beam.agentsim.infrastructure.ChargingNetworkManager.ChargingZone
import beam.agentsim.infrastructure.parking.ParkingZoneFileUtils.ParkingLoadingAccumulator
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.ZoneSearchTree
import beam.agentsim.infrastructure.parking.{GeoLevel, ParkingNetwork, ParkingZone, ParkingZoneFileUtils}
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.sim.BeamServices
import beam.sim.vehiclesharing.Fleets
import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.matsim.core.utils.collections.QuadTree
import org.matsim.api.core.v01.network.Network

import scala.collection.JavaConverters._
import scala.util.{Failure, Random, Success, Try}
import scala.language.existentials

case class ParkingAndChargingInfrastructure(beamServices: BeamServices, envelopeInUTM: Envelope) {
  import ParkingAndChargingInfrastructure._
  import beamServices._

  // RIDEHAIL
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

  val (chargingNetworks: Map[Option[Id[VehicleManager]], QuadTree[ChargingZone]], parkingNetworks: ParkingNetwork[_]) =
    buildChargingAndParkingNetwork(beamServices, envelopeInUTM, mainParkingFile, vehicleManagersParkingFiles)

}

object ParkingAndChargingInfrastructure extends LazyLogging {

  private def buildChargingAndParkingNetwork(
    beamServices: BeamServices,
    envelopeInUTM: Envelope,
    mainParkingFile: String,
    vehicleManagersParkingFiles: IndexedSeq[String]
  ): (Map[Option[Id[VehicleManager]], QuadTree[ChargingZone]], ParkingNetwork[_]) = {
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
              loadChargingZones[TAZ](
                stalls,
                "taz",
                beamScenario.tazTreeMap,
                beamScenario.network,
                envelopeInUTM: Envelope,
                beamConfig.beam.spatial.boundingBoxBuffer
              ),
              ZonalParkingManager.init(
                beamConfig,
                beamScenario.tazTreeMap.tazQuadTree,
                beamScenario.tazTreeMap.idToTAZMapping,
                identity[TAZ](_),
                geo,
                envelopeInUTM,
                stalls,
                searchTree
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
              loadChargingZones[Link](
                stalls,
                "link",
                beamScenario.tazTreeMap,
                beamScenario.network,
                envelopeInUTM: Envelope,
                beamConfig.beam.spatial.boundingBoxBuffer
              ),
              ZonalParkingManager.init(
                beamScenario.beamConfig,
                beamScenario.linkQuadTree,
                beamScenario.linkIdMapping,
                beamScenario.linkToTAZMapping,
                geo,
                envelopeInUTM,
                stalls,
                searchTree
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
        (
          loadChargingZones[Link](
            stalls,
            "link",
            beamScenario.tazTreeMap,
            beamScenario.network,
            envelopeInUTM: Envelope,
            beamConfig.beam.spatial.boundingBoxBuffer
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
          loadChargingZones[TAZ](
            stalls,
            "taz",
            beamScenario.tazTreeMap,
            beamScenario.network,
            envelopeInUTM: Envelope,
            beamConfig.beam.spatial.boundingBoxBuffer
          ),
          ParallelParkingManager.init(beamConfig, beamScenario.tazTreeMap, stalls, searchTree, geo, envelopeInUTM)
        )

      case unknown @ _ => throw new IllegalArgumentException(s"Unknown parking manager type: $unknown")
    }
  }

  /**
    * load parking stalls with charging point
    * @return QuadTree of ChargingZone
    */
  private def loadChargingZones[GEO: GeoLevel](
    parkingZones: Array[ParkingZone[GEO]],
    geoLevel: String,
    tazTreeMap: TAZTreeMap,
    network: Network,
    envelopeInUTM: Envelope,
    boundingBoxBuffer: Int
  ) = {
    val zonesWithCharger = parkingZones.filter(_.chargingPointType.isDefined).map { z =>
      val coord = geoLevel.toLowerCase match {
        case "taz"  => tazTreeMap.getTAZ(z.geoId.asInstanceOf[Id[TAZ]]).get.coord
        case "link" => network.getLinks.get(z.geoId.asInstanceOf[Id[Link]]).getCoord
        case _ =>
          throw new IllegalArgumentException(s"Unsupported parking level type $geoLevel, only TAZ | Link are supported")
      }
      (z, coord)
    }
    val coordinates = zonesWithCharger.map(_._2)
    val xs = coordinates.map(_.getX)
    val ys = coordinates.map(_.getY)
    envelopeInUTM.expandBy(boundingBoxBuffer)
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
                tazTreeMap.getTAZ(coord).tazId,
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

  def loadParkingZones[GEO: GeoLevel](
    parkingFilePath: String,
    depotFilePaths: IndexedSeq[String],
    geoQuadTree: QuadTree[GEO],
    parkingStallCountScalingFactor: Double,
    parkingCostScalingFactor: Double,
    seed: Long
  ): (Array[ParkingZone[GEO]], ZoneSearchTree[GEO]) = {
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
    (parkingLoadingAccumulator.zones.toArray, parkingLoadingAccumulator.tree)
  }

}
