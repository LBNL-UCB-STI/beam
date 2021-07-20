package beam.agentsim.infrastructure.power

import beam.agentsim.agents.vehicles.VehicleManager
import beam.agentsim.infrastructure.ChargingNetwork
import beam.agentsim.infrastructure.ChargingNetwork.ChargingStation
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.{ParkingType, PricingModel}
import beam.agentsim.infrastructure.power.SitePowerManager.PhysicalBounds
import beam.agentsim.infrastructure.taz.TAZ
import beam.cosim.helics.BeamHelicsInterface._
import beam.sim.config.BeamConfig
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id

import scala.util.control.NonFatal
import scala.util.{Failure, Try}

class PowerController(
  chargingNetworkMap: Map[Id[VehicleManager], ChargingNetwork[_]],
  chargingNetworkManagerConfig: BeamConfig.Beam.Agentsim.ChargingNetworkManager,
  unlimitedPhysicalBounds: Map[ChargingStation, PhysicalBounds]
) extends LazyLogging {
  import SitePowerManager._

  private val helicsConfig = chargingNetworkManagerConfig.helics

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
    }.recoverWith { case e =>
      logger.warn("Cannot init BeamFederate: {}. ChargingNetworkManager is not connected to the grid", e.getMessage)
      Failure(e)
    }.toOption
  } else None

  private var physicalBounds = Map.empty[ChargingStation, PhysicalBounds]
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
          if helicsConfig.connectionEnabled && estimatedLoad.isDefined && (physicalBounds.isEmpty || currentBin < currentTime / chargingNetworkManagerConfig.timeStepInSeconds) =>
        logger.debug("Sending power over next planning horizon to the grid at time {}...", currentTime)
        // PUBLISH
        val msgToPublish = estimatedLoad.get.map { case (station, powerInKW) =>
          Map(
            "vehicleManager"    -> station.zone.vehicleManagerId,
            "taz"               -> station.zone.geoId.toString,
            "parkingType"       -> station.zone.parkingType.toString,
            "chargingPointType" -> station.zone.chargingPointType.toString,
            "numChargers"       -> station.zone.maxStalls,
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
            case managerIdString if managerIdString.isEmpty => VehicleManager.defaultManager
            case managerIdString                            => Id.create(managerIdString, classOf[VehicleManager])
          }
          val chargingNetwork = chargingNetworkMap(managerId)
          chargingNetwork.lookupStation(
            Id.create(x("taz").asInstanceOf[String], classOf[TAZ]),
            ParkingType(x("parkingType").asInstanceOf[String]),
            ChargingPointType(x("chargingPointType").asInstanceOf[String]),
            PricingModel(x("pricingModel").asInstanceOf[String], x("feeInCents").asInstanceOf[String])
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
    currentBin = currentTime / chargingNetworkManagerConfig.timeStepInSeconds
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
