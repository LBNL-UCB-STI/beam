package beam.agentsim.infrastructure.power

import beam.agentsim.agents.vehicles.VehicleManager
import beam.agentsim.infrastructure.ChargingNetwork
import beam.agentsim.infrastructure.ChargingNetwork.ChargingStation
import beam.agentsim.infrastructure.ChargingNetworkManager.ChargingZone
import beam.cosim.helics.BeamHelicsInterface._
import beam.sim.config.BeamConfig
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id

import scala.collection.concurrent.TrieMap
import scala.util.control.NonFatal
import scala.util.{Failure, Try}

class PowerController(chargingNetworkMap: TrieMap[Id[VehicleManager], ChargingNetwork], beamConfig: BeamConfig)
    extends LazyLogging {
  import ChargingZone._
  import SitePowerManager._

  private val cnmCfg = beamConfig.beam.agentsim.chargingNetworkManager

  private[power] lazy val beamFederateOption: Option[BeamFederate] = if (cnmCfg.gridConnectionEnabled) {
    logger.info("ChargingNetworkManager should be connected to a grid model...")
    Try {
      logger.debug("Init PowerController resources...")
      getFederate(
        cnmCfg.helicsFederateName,
        cnmCfg.helicsDataOutStreamPoint match {
          case s: String if s.nonEmpty => Some(s)
          case _                       => None
        },
        cnmCfg.helicsDataInStreamPoint match {
          case s: String if s.nonEmpty => Some((s, cnmCfg.helicsBufferSize))
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
    if (physicalBounds.isEmpty || currentBin < currentTime / cnmCfg.timeStepInSeconds) {
      physicalBounds = beamFederateOption match {
        case Some(beamFederate) if cnmCfg.gridConnectionEnabled && estimatedLoad.isDefined =>
          logger.debug("Sending power over next planning horizon to the grid at time {}...", currentTime)
          beamFederate.publishJSON(
            estimatedLoad.get.map {
              case (station, powerInKW) =>
                from(station.zone) ++ Map("estimatedLoad" -> powerInKW)
            }.toList
          )
          val (_, gridBounds) = beamFederate.syncAndCollectJSON(currentTime)
          logger.debug("Obtained power from the grid {}...", gridBounds)
          gridBounds.map { x =>
            val station = chargingStationsMap(to(x))
            station -> PhysicalBounds(station, x("maxLoad").asInstanceOf[PowerInKW])
          }.toMap
        case _ =>
          logger.debug("Not connected to grid, falling to default physical bounds at time {}...", currentTime)
          unlimitedPhysicalBounds
      }
      currentBin = currentTime / cnmCfg.timeStepInSeconds
    }
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
