package beam.agentsim.infrastructure.power

import beam.agentsim.infrastructure.ChargingNetworkManager
import beam.agentsim.infrastructure.ChargingNetworkManager._
import beam.agentsim.infrastructure.taz.TAZ
import beam.cosim.helics.BeamHelicsInterface._
import beam.sim.config.BeamConfig
import org.matsim.api.core.v01.Id
import org.slf4j.{Logger, LoggerFactory}

import scala.util.control.NonFatal
import scala.util.{Failure, Try}

class PowerController(beamConfig: BeamConfig) {

  private val logger: Logger = LoggerFactory.getLogger(classOf[PowerController])
  private val cnmCfg = beamConfig.beam.agentsim.chargingNetworkManager

  private[power] lazy val beamFederateOption: Option[BeamFederate] = if (cnmCfg.gridConnectionEnabled) {
    logger.info("ChargingNetworkManager should be connected to a grid model...")
    Try {
      logger.debug("Init PowerController resources...")
      getFederateInstance(
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

  private var physicalBounds = Map.empty[Int, PhysicalBounds]
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
    chargingStationsMap: Map[Int, ChargingZone],
    estimatedLoad: Option[Map[Int, PowerInKW]]
  ): Map[Int, PhysicalBounds] = {
    if (physicalBounds.isEmpty || currentBin < currentTime / cnmCfg.timeStepInSeconds) {
      physicalBounds = beamFederateOption match {
        case Some(beamFederate) if cnmCfg.gridConnectionEnabled && estimatedLoad.isDefined =>
          logger.debug("Sending power over next planning horizon to the grid at time {}...", currentTime)
          beamFederate.publishJSON(
            estimatedLoad.get
              .map(
                x =>
                  Map(
                    "tazId"   -> chargingStationsMap(x._1).tazId.toString,
                    "zoneId"  -> chargingStationsMap(x._1).parkingZoneId,
                    "minLoad" -> x._2,
                    "maxLoad" -> x._2
                )
              )
              .toList
          )
          val (_, gridBounds) = beamFederate.syncAndCollectJSON(currentTime)
          logger.debug("Obtained power from the grid {}...", gridBounds)
          gridBounds
            .map(
              x =>
                x("zoneId").asInstanceOf[Int] ->
                PhysicalBounds(
                  Id.create(x("tazId").asInstanceOf[String], classOf[TAZ]),
                  x("zoneId").asInstanceOf[Int],
                  x("minLoad").asInstanceOf[Double],
                  x("maxLoad").asInstanceOf[Double]
              )
            )
            .toMap
        case _ =>
          logger.debug("Not connected to grid, falling to default physical bounds at time {}...", currentTime)
          // estimatedLoad.map { case (k, v) => k.parkingZoneId -> PhysicalBounds(k.tazId, k.parkingZoneId, v, v) }
          ChargingNetworkManager.unlimitedPhysicalBounds(chargingStationsMap).value
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
