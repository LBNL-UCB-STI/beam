package beam.agentsim.infrastructure

import beam.agentsim.agents.ridehail.DefaultRideHailDepotParkingManager
import beam.agentsim.agents.vehicles.VehicleManager
import beam.agentsim.infrastructure.parking.ParkingZoneFileUtils.ParkingLoadingAccumulator
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.TAZ
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.sim.vehiclesharing.Fleets
import beam.sim.{BeamScenario, BeamServices}
import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.matsim.core.utils.collections.QuadTree

import scala.collection.JavaConverters._
import scala.language.existentials
import scala.util.{Failure, Random, Success, Try}

object InfrastructureUtils extends LazyLogging {

  /**
    * @param beamScenario
    * @param beamConfig
    * @param geo
    * @param envelopeInUTM
    * @return
    */
  def buildParkingAndChargingNetworks(
    beamServices: BeamServices,
    envelopeInUTM: Envelope
  ): (ParkingNetwork[_], Map[Id[VehicleManager], ChargingNetwork[_]]) = {
    implicit val beamScenario: BeamScenario = beamServices.beamScenario
    implicit val geo: GeoUtils = beamServices.geo
    implicit val boundingBox: Envelope = envelopeInUTM
    val beamConfig = beamServices.beamConfig
    val parkingManagerCfg = beamConfig.beam.agentsim.taz.parkingManager

    val mainParkingFile: String = beamConfig.beam.agentsim.taz.parkingFilePath
    // ADD HERE ALL PARKING FILES THAT BELONGS TO VEHICLE MANAGERS
    val vehicleManagersParkingFiles: IndexedSeq[(String, Id[VehicleManager], Seq[ParkingType])] = {
      // SHARED FLEET
      val sharedFleetsParkingFiles =
        beamConfig.beam.agentsim.agents.vehicles.sharedFleets
          .map(Fleets.lookup)
          .map(x => (x.parkingFilePath, x.vehicleManagerId, Seq(ParkingType.Public)))
      // FREIGHT
      val freightParkingFile = List(
        (
          beamConfig.beam.agentsim.agents.freight.carrierParkingFilePath.getOrElse(""),
          VehicleManager
            .createOrGetIdUsingUnique(beamConfig.beam.agentsim.agents.freight.name, VehicleManager.BEAMFreight),
          Seq(ParkingType.Workplace)
        )
      )
      // RIDEHAIL
      val ridehailParkingFile = List(
        (
          beamConfig.beam.agentsim.agents.rideHail.initialization.parking.filePath,
          VehicleManager
            .createOrGetIdUsingUnique(beamConfig.beam.agentsim.agents.rideHail.name, VehicleManager.BEAMRideHail),
          Seq(ParkingType.Workplace).toList
        )
      )
      (sharedFleetsParkingFiles ++ freightParkingFile ++ ridehailParkingFile).toIndexedSeq
    }

    // STALLS ARE LOADED HERE
    logger.info(s"loading stalls...")
    val stalls = beamConfig.beam.agentsim.taz.parkingManager.level.toLowerCase match {
      case "taz" =>
        loadStalls[TAZ](
          mainParkingFile,
          vehicleManagersParkingFiles,
          beamScenario.tazTreeMap.tazQuadTree,
          beamScenario.beamConfig.beam.agentsim.taz.parkingStallCountScalingFactor,
          beamScenario.beamConfig.beam.agentsim.taz.parkingCostScalingFactor,
          beamScenario.beamConfig.matsim.modules.global.randomSeed,
          beamScenario.beamConfig
        )
      case "link" =>
        loadStalls[Link](
          mainParkingFile,
          vehicleManagersParkingFiles,
          beamScenario.linkQuadTree,
          beamScenario.beamConfig.beam.agentsim.taz.parkingStallCountScalingFactor,
          beamScenario.beamConfig.beam.agentsim.taz.parkingCostScalingFactor,
          beamScenario.beamConfig.matsim.modules.global.randomSeed,
          beamScenario.beamConfig
        )
      case _ =>
        throw new IllegalArgumentException(
          s"Unsupported parking level type ${parkingManagerCfg.level}, only TAZ | Link are supported"
        )
    }

    // CHARGING ZONES ARE BUILT HERE
    logger.info(s"building charging networks...")
    val chargingNetworks = beamConfig.beam.agentsim.taz.parkingManager.level.toLowerCase match {
      case "taz" =>
        buildChargingZones[TAZ](stalls.asInstanceOf[Map[Id[ParkingZoneId], ParkingZone[TAZ]]]).map {
          case (managerId, chargingZones) => {
            managerId -> (VehicleManager.getType(managerId) match {
              case VehicleManager.BEAMRideHail =>
                DefaultRideHailDepotParkingManager.init(managerId, chargingZones, envelopeInUTM, beamServices)
              case _ => ChargingNetwork.init(managerId, chargingZones, envelopeInUTM, beamServices)
            })
          }
        }
      case "link" =>
        buildChargingZones[Link](stalls.asInstanceOf[Map[Id[ParkingZoneId], ParkingZone[Link]]]).map {
          case (managerId, chargingZones) =>
            managerId -> (VehicleManager.getType(managerId) match {
              case VehicleManager.BEAMRideHail =>
                DefaultRideHailDepotParkingManager.init(
                  managerId,
                  chargingZones,
                  beamScenario.linkQuadTree,
                  beamScenario.linkIdMapping,
                  beamScenario.linkToTAZMapping,
                  envelopeInUTM,
                  beamServices
                )
              case _ =>
                ChargingNetwork.init(
                  managerId,
                  chargingZones,
                  beamScenario.linkQuadTree,
                  beamScenario.linkIdMapping,
                  beamScenario.linkToTAZMapping,
                  envelopeInUTM,
                  beamServices
                )
            })
        }
      case _ =>
        throw new IllegalArgumentException(
          s"Unsupported parking level type ${parkingManagerCfg.level}, only TAZ | Link are supported"
        )
    }

    // PARKING ZONES ARE BUILT HERE
    logger.info(s"building parking networks...")
    val parkingNetworks = beamConfig.beam.agentsim.taz.parkingManager.method match {
      case "DEFAULT" =>
        beamConfig.beam.agentsim.taz.parkingManager.level.toLowerCase match {
          case "taz" =>
            ZonalParkingManager.init(
              buildParkingZones(stalls.asInstanceOf[Map[Id[ParkingZoneId], ParkingZone[TAZ]]]),
              envelopeInUTM,
              beamServices
            )
          case "link" =>
            ZonalParkingManager.init(
              buildParkingZones(stalls.asInstanceOf[Map[Id[ParkingZoneId], ParkingZone[Link]]]),
              beamScenario.linkQuadTree,
              beamScenario.linkIdMapping,
              beamScenario.linkToTAZMapping,
              envelopeInUTM,
              beamServices
            )
          case _ =>
            throw new IllegalArgumentException(
              s"Unsupported parking level type ${parkingManagerCfg.level}, only TAZ | Link are supported"
            )
        }
      case "HIERARCHICAL" =>
        HierarchicalParkingManager
          .init(
            buildParkingZones(stalls.asInstanceOf[Map[Id[ParkingZoneId], ParkingZone[Link]]]),
            beamScenario.tazTreeMap,
            beamScenario.linkToTAZMapping,
            geo.distUTMInMeters(_, _),
            beamConfig.beam.agentsim.agents.parking.minSearchRadius,
            beamConfig.beam.agentsim.agents.parking.maxSearchRadius,
            envelopeInUTM,
            beamConfig.matsim.modules.global.randomSeed,
            beamConfig.beam.agentsim.agents.parking.mulitnomialLogit
          )
      case "PARALLEL" =>
        ParallelParkingManager.init(
          buildParkingZones(stalls.asInstanceOf[Map[Id[ParkingZoneId], ParkingZone[TAZ]]]),
          beamScenario.beamConfig,
          beamScenario.tazTreeMap,
          geo.distUTMInMeters,
          envelopeInUTM
        )
      case unknown @ _ => throw new IllegalArgumentException(s"Unknown parking manager type: $unknown")
    }
    (parkingNetworks, chargingNetworks)
  }

  /**
    * @param parkingFilePath
    * @param depotFilePaths
    * @param geoQuadTree
    * @param parkingStallCountScalingFactor
    * @param parkingCostScalingFactor
    * @param seed
    * @tparam GEO
    * @return
    */
  def loadStalls[GEO: GeoLevel](
    parkingFilePath: String,
    depotFilePaths: IndexedSeq[(String, Id[VehicleManager], Seq[ParkingType])],
    geoQuadTree: QuadTree[GEO],
    parkingStallCountScalingFactor: Double,
    parkingCostScalingFactor: Double,
    seed: Long,
    beamConfig: BeamConfig
  ): Map[Id[ParkingZoneId], ParkingZone[GEO]] = {
    val random = new Random(seed)
    val initialAccumulator: ParkingLoadingAccumulator[GEO] = if (parkingFilePath.isEmpty) {
      ParkingZoneFileUtils.generateDefaultParkingAccumulatorFromGeoObjects(
        geoQuadTree.values().asScala,
        random,
        ParkingZone.GlobalReservedFor
      )
    } else {
      Try {
        ParkingZoneFileUtils.fromFileToAccumulator(
          parkingFilePath,
          random,
          Some(beamConfig),
          parkingStallCountScalingFactor,
          parkingCostScalingFactor
        )
      } match {
        case Success(accumulator) => accumulator
        case Failure(e) =>
          logger.error(s"unable to read contents of provided parking file $parkingFilePath", e)
          ParkingZoneFileUtils.generateDefaultParkingAccumulatorFromGeoObjects(
            geoQuadTree.values().asScala,
            random,
            ParkingZone.GlobalReservedFor
          )
      }
    }
    val parkingLoadingAccumulator = depotFilePaths.foldLeft(initialAccumulator) {
      case (acc, (filePath, defaultVehicleManager, defaultParkingTypes)) =>
        filePath.trim match {
          case "" if VehicleManager.getType(defaultVehicleManager) == VehicleManager.BEAMRideHail =>
            ParkingZoneFileUtils.generateDefaultParkingAccumulatorFromGeoObjects(
              geoQuadTree.values().asScala,
              random,
              defaultVehicleManager,
              defaultParkingTypes,
              acc
            )
          case "" =>
            acc
          case depotParkingFilePath =>
            Try {
              ParkingZoneFileUtils.fromFileToAccumulator(
                depotParkingFilePath,
                random,
                Some(beamConfig),
                parkingStallCountScalingFactor,
                parkingCostScalingFactor,
                acc
              )
            } match {
              case Success(accumulator) => accumulator
              case Failure(e) =>
                logger.warn(s"unable to read contents of provided parking file $depotParkingFilePath", e)
                acc
            }
        }
    }
    parkingLoadingAccumulator.zones.toMap
  }

  /**
    * @param stalls Map[Id[ParkingZoneId], ParkingZone[GEO]]
    * @return
    */
  def buildParkingZones[GEO: GeoLevel](
    stalls: Map[Id[ParkingZoneId], ParkingZone[GEO]]
  ): Map[Id[ParkingZoneId], ParkingZone[GEO]] = stalls.filter(_._2.chargingPointType.isEmpty)

  /**
    * @param stalls Map[Id[ParkingZoneId], ParkingZone[GEO]]
    * @return
    */
  def buildChargingZones[GEO: GeoLevel](
    stalls: Map[Id[ParkingZoneId], ParkingZone[GEO]]
  ): Map[Id[VehicleManager], Map[Id[ParkingZoneId], ParkingZone[GEO]]] = {
    stalls
      .filter(_._2.chargingPointType.isDefined)
      .groupBy(_._2.reservedFor)
  }

}
