package beam.agentsim.infrastructure.power

import beam.agentsim.agents.vehicles.VehicleManager
import beam.agentsim.infrastructure.ChargingNetwork
import beam.agentsim.infrastructure.ChargingNetwork.ChargingStation
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.ParkingType
import beam.agentsim.infrastructure.taz.TAZ
import beam.cosim.helics.BeamHelicsInterface._
import beam.sim.config.BeamConfig
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id

import scala.util.control.NonFatal
import scala.util.{Failure, Try}

class PowerController(chargingNetworkMap: Map[Option[Id[VehicleManager]], ChargingNetwork], beamConfig: BeamConfig)
    extends LazyLogging {
  import SitePowerManager._

  private val cnmConfig = beamConfig.beam.agentsim.chargingNetworkManager
  private val helicsConfig = cnmConfig.helics

  private[power] lazy val beamFederateOption: Option[BeamFederate] = if (helicsConfig.connectionEnabled) {
    logger.info("ChargingNetworkManager should be connected to a grid model...")
    Try {
      logger.debug("Init PowerController resources...")
      getFederate(
        helicsConfig.federateName,
        helicsConfig.coreType,
        helicsConfig.coreInitString,
        helicsConfig.timeDeltaProperty,
        helicsConfig.intLogLevel,
        helicsConfig.bufferSize,
        helicsConfig.dataOutStreamPoint match {
          case s: String if s.nonEmpty => Some(s)
          case _                       => None
        },
        helicsConfig.dataInStreamPoint match {
          case s: String if s.nonEmpty => Some(s)
          case _                       => None
        }
      )
    }.recoverWith {
      case e =>
        logger.warn("Cannot init BeamFederate: {}. ChargingNetworkManager is not connected to the grid", e.getMessage)
        Failure(e)
    }.toOption
  } else None

  private var physicalBounds = Map.empty[ChargingStation, PhysicalBounds]
  private val chargingStationsMap = chargingNetworkMap.flatMap(_._2.chargingStations).map(s => s.zone -> s).toMap
  private val unlimitedPhysicalBounds = getUnlimitedPhysicalBounds(chargingStationsMap.values.toList.distinct).value
  private var currentBin = -1

  /**
    * Obtains physical bounds from the grid
    *
    * @param currentTime current time
    *  @param estimatedLoad map required power per zone
    * @return tuple of PhysicalBounds and Int (next time)
    */
  def obtainPowerPhysicalBounds(
    currentTime: Int,
    estimatedLoad: Option[Map[ChargingStation, PowerInKW]] = None
  ): Map[ChargingStation, PhysicalBounds] = {
    physicalBounds = beamFederateOption match {
      case Some(beamFederate)
          if helicsConfig.connectionEnabled && estimatedLoad.isDefined && (physicalBounds.isEmpty || currentBin < currentTime / cnmConfig.timeStepInSeconds) =>
        logger.debug("Sending power over next planning horizon to the grid at time {}...", currentTime)
        // PUBLISH
        val msgToPublish = estimatedLoad.get.map {
          case (station, powerInKW) =>
            Map(
              "vehicleManager"    -> station.zone.vehicleManager,
              "taz"               -> station.zone.geoId.toString,
              "parkingType"       -> station.zone.parkingType.toString,
              "chargingPointType" -> station.zone.chargingPointType.toString,
              "numChargers"       -> station.zone.numChargers,
              "estimatedLoad"     -> powerInKW
            )
        }
        beamFederate.publishJSON(msgToPublish.toList)

        var gridBounds = List.empty[Map[String, Any]]
        while (gridBounds.isEmpty) {
          // SYNC
          beamFederate.sync(currentTime)
          // COLLECT
          gridBounds = beamFederate.collectJSON()
          // Sleep
          Thread.sleep(1)
        }

        logger.debug("Obtained power from the grid {}...", gridBounds)
        gridBounds.flatMap { x =>
          val managerId = x("vehicleManager").asInstanceOf[String] match {
            case managerIdString if managerIdString.isEmpty => None
            case managerIdString                            => Some(Id.create(managerIdString, classOf[VehicleManager]))
          }
          val chargingNetwork = chargingNetworkMap(managerId)
          chargingNetwork.lookupStation(
            Id.create(x("taz").asInstanceOf[String], classOf[TAZ]),
            ParkingType(x("parkingType").asInstanceOf[String]),
            ChargingPointType(x("chargingPointType").asInstanceOf[String]).get
          ) match {
            case Some(station) =>
              Some(
                station -> PhysicalBounds(
                  station,
                  x("power_limit_upper").asInstanceOf[PowerInKW],
                  x("power_limit_lower").asInstanceOf[PowerInKW],
                  x("lmp_with_control_signal").asInstanceOf[Double]
                )
              )
            case _ =>
              logger.error(
                "Cannot find the charging station correspondent to what has been received from the co-simulation"
              )
              None
          }
        }.toMap
      case _ =>
        logger.debug("Not connected to grid, falling to default physical bounds at time {}...", currentTime)
        unlimitedPhysicalBounds
    }
    currentBin = currentTime / cnmConfig.timeStepInSeconds
    physicalBounds
  }

  /**
    * closing helics connection
    */
  def close(): Unit = {
    logger.debug("Release PowerController resources...")
    beamFederateOption
      .fold(logger.debug("Not connected to grid, just releasing helics resources")) { beamFederate =>
        beamFederate.close()
        try {
          logger.debug("Destroying BeamFederate")
          unloadHelics()
        } catch {
          case NonFatal(ex) =>
            logger.error(s"Cannot destroy BeamFederate: ${ex.getMessage}")
        }
      }
  }
}
