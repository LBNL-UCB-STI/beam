package beam.agentsim.infrastructure

import beam.agentsim.agents.ridehail.{DefaultRideHailDepotParkingManager, RideHailDepotParkingManager}
import beam.agentsim.agents.vehicles.VehicleManager
import beam.agentsim.agents.vehicles.VehicleManager.ReservedFor
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
  ): (ParkingNetwork[_], ChargingNetwork[_], RideHailDepotParkingManager[_]) = {
    implicit val beamScenario: BeamScenario = beamServices.beamScenario
    implicit val geo: GeoUtils = beamServices.geo
    implicit val boundingBox: Envelope = envelopeInUTM
    val beamConfig = beamServices.beamConfig
    val parkingManagerCfg = beamConfig.beam.agentsim.taz.parkingManager

    val mainParkingFile: String = beamConfig.beam.agentsim.taz.parkingFilePath
    val parkingStallCountScalingFactor: Double = beamConfig.beam.agentsim.taz.parkingStallCountScalingFactor
    val parkingCostScalingFactor: Double = beamConfig.beam.agentsim.taz.parkingCostScalingFactor

    val (mainChargingFile, chargingStallCountScalingFactor, chargingCostScalingFactor) =
      if (beamConfig.beam.agentsim.chargingNetworkManager.chargingPointFilePath.isEmpty)
        (mainParkingFile, parkingStallCountScalingFactor, parkingCostScalingFactor)
      else
        (
          beamConfig.beam.agentsim.chargingNetworkManager.chargingPointFilePath,
          beamConfig.beam.agentsim.chargingNetworkManager.chargingPointCountScalingFactor,
          beamConfig.beam.agentsim.chargingNetworkManager.chargingPointCostScalingFactor
        )
    // ADD HERE ALL PARKING FILES THAT BELONGS TO VEHICLE MANAGERS
    val vehicleManagersParkingFiles: IndexedSeq[(String, ReservedFor, Seq[ParkingType])] = {
      // SHARED FLEET
      val sharedFleetsParkingFiles =
        beamConfig.beam.agentsim.agents.vehicles.sharedFleets
          .map(Fleets.lookup)
          .map(x => (x.parkingFilePath, VehicleManager.getReservedFor(x.vehicleManagerId).get, Seq(ParkingType.Public)))
      // FREIGHT
      val freightParkingFile = List(
        (
          beamConfig.beam.agentsim.agents.freight.carrierParkingFilePath.getOrElse(""),
          VehicleManager
            .createOrGetReservedFor(beamConfig.beam.agentsim.agents.freight.name, VehicleManager.TypeEnum.Freight),
          Seq(ParkingType.Workplace)
        )
      )
      // RIDEHAIL
      val ridehailParkingFile = List(
        (
          beamConfig.beam.agentsim.agents.rideHail.initialization.parking.filePath,
          VehicleManager
            .createOrGetReservedFor(beamConfig.beam.agentsim.agents.rideHail.name, VehicleManager.TypeEnum.RideHail),
          Seq(ParkingType.Workplace).toList
        )
      )
      (sharedFleetsParkingFiles ++ freightParkingFile ++ ridehailParkingFile).toIndexedSeq
    }

    // CHARGING STALLS ARE LOADED HERE
    val allChargingStalls = loadStalls[TAZ](
      mainChargingFile,
      vehicleManagersParkingFiles,
      beamScenario.tazTreeMap.tazQuadTree,
      chargingStallCountScalingFactor,
      chargingCostScalingFactor,
      beamScenario.beamConfig.matsim.modules.global.randomSeed,
      beamScenario.beamConfig,
      Some(beamServices)
    )
    val chargingStalls = loadChargingStalls(allChargingStalls)
    val rideHailChargingStalls = loadRideHailChargingStalls(allChargingStalls)

    // CHARGING ZONES ARE BUILT HERE
    logger.info(s"building charging networks...")
    val (nonRhChargingNetwork, rhChargingNetwork) = (
      ChargingNetwork.init(
        chargingStalls,
        envelopeInUTM,
        beamServices
      ),
      rideHailChargingStalls.map { case (managerId, chargingZones) =>
        DefaultRideHailDepotParkingManager.init(
          managerId,
          chargingZones,
          envelopeInUTM,
          beamServices
        )
      }.head
    )

    // PARKING STALLS ARE LOADED HERE
    logger.info(s"loading stalls...")
    val parkingStalls = beamConfig.beam.agentsim.taz.parkingManager.level.toLowerCase match {
      case "taz" =>
        loadParkingStalls(
          loadStalls[TAZ](
            mainParkingFile,
            vehicleManagersParkingFiles,
            beamScenario.tazTreeMap.tazQuadTree,
            parkingStallCountScalingFactor,
            parkingCostScalingFactor,
            beamScenario.beamConfig.matsim.modules.global.randomSeed,
            beamScenario.beamConfig,
            Some(beamServices)
          )
        )
      case "link" =>
        loadParkingStalls(
          loadStalls[Link](
            mainParkingFile,
            vehicleManagersParkingFiles,
            beamScenario.linkQuadTree,
            parkingStallCountScalingFactor,
            parkingCostScalingFactor,
            beamScenario.beamConfig.matsim.modules.global.randomSeed,
            beamScenario.beamConfig,
            Some(beamServices)
          )
        )
      case _ =>
        throw new IllegalArgumentException(
          s"Unsupported parking level type ${parkingManagerCfg.level}, only TAZ | Link are supported"
        )
    }
    logger.info(s"building parking networks...")
    val parkingNetwork = beamConfig.beam.agentsim.taz.parkingManager.method match {
      case "DEFAULT" =>
        beamConfig.beam.agentsim.taz.parkingManager.level.toLowerCase match {
          case "taz" =>
            ZonalParkingManager.init(
              parkingStalls.asInstanceOf[Map[Id[ParkingZoneId], ParkingZone[TAZ]]],
              envelopeInUTM,
              beamServices
            )
          case "link" =>
            ZonalParkingManager.init(
              parkingStalls.asInstanceOf[Map[Id[ParkingZoneId], ParkingZone[Link]]],
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
            parkingStalls.asInstanceOf[Map[Id[ParkingZoneId], ParkingZone[Link]]],
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
          parkingStalls.asInstanceOf[Map[Id[ParkingZoneId], ParkingZone[TAZ]]],
          beamScenario.beamConfig,
          beamScenario.tazTreeMap,
          geo.distUTMInMeters,
          envelopeInUTM
        )
      case unknown @ _ => throw new IllegalArgumentException(s"Unknown parking manager type: $unknown")
    }
    (parkingNetwork, nonRhChargingNetwork, rhChargingNetwork)
  }

  /**
    * @param parkingFilePath parking file path
    * @param depotFilePaths depot file paths
    * @param geoQuadTree geo guad
    * @param parkingStallCountScalingFactor parking stall count
    * @param parkingCostScalingFactor parking cost
    * @param seed random seed
    * @param beamConfig beam config
    * @return
    */
  def loadStalls[GEO: GeoLevel](
    parkingFilePath: String,
    depotFilePaths: IndexedSeq[(String, ReservedFor, Seq[ParkingType])],
    geoQuadTree: QuadTree[GEO],
    parkingStallCountScalingFactor: Double,
    parkingCostScalingFactor: Double,
    seed: Long,
    beamConfig: BeamConfig,
    beamServicesMaybe: Option[BeamServices]
  ): Map[Id[ParkingZoneId], ParkingZone[GEO]] = {
    val random = new Random(seed)
    val initialAccumulator: ParkingLoadingAccumulator[GEO] = if (parkingFilePath.isEmpty) {
      ParkingZoneFileUtils.generateDefaultParkingAccumulatorFromGeoObjects(
        geoQuadTree.values().asScala,
        random,
        VehicleManager.AnyManager
      )
    } else {
      Try {
        ParkingZoneFileUtils.fromFileToAccumulator(
          parkingFilePath,
          random,
          Some(beamConfig),
          beamServicesMaybe,
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
            VehicleManager.AnyManager
          )
      }
    }
    val parkingLoadingAccumulator = depotFilePaths.foldLeft(initialAccumulator) {
      case (acc, (filePath, defaultReservedFor, defaultParkingTypes)) =>
        filePath.trim match {
          case "" if defaultReservedFor.managerType == VehicleManager.TypeEnum.RideHail =>
            ParkingZoneFileUtils.generateDefaultParkingAccumulatorFromGeoObjects(
              geoQuadTree.values().asScala,
              random,
              defaultReservedFor,
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
                beamServicesMaybe,
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
  def loadParkingStalls[GEO](
    stalls: Map[Id[ParkingZoneId], ParkingZone[GEO]]
  ): Map[Id[ParkingZoneId], ParkingZone[GEO]] = stalls.filter(_._2.chargingPointType.isEmpty)

  /**
    * @param stalls list of parking zones
    * @return
    */
  def loadRideHailChargingStalls[GEO](
    stalls: Map[Id[ParkingZoneId], ParkingZone[GEO]]
  ): Map[Id[VehicleManager], Map[Id[ParkingZoneId], ParkingZone[GEO]]] = {
    import VehicleManager._
    stalls
      .filter(x => x._2.chargingPointType.nonEmpty && x._2.reservedFor.managerType == TypeEnum.RideHail)
      .groupBy(_._2.reservedFor.managerId)
  }

  /**
    * @param stalls list of parking zones
    * @return
    */
  def loadChargingStalls[GEO](
    stalls: Map[Id[ParkingZoneId], ParkingZone[GEO]]
  ): Map[Id[ParkingZoneId], ParkingZone[GEO]] = {
    import VehicleManager._
    stalls.filter(x => x._2.chargingPointType.nonEmpty && x._2.reservedFor.managerType != TypeEnum.RideHail)
  }
}
