package beam.agentsim.infrastructure.power

import beam.agentsim.infrastructure.ChargingNetworkManager.ChargingZone
import beam.agentsim.infrastructure.power.SitePowerManager.{PhysicalBounds, PowerInKW, ZoneId}
import beam.agentsim.infrastructure.taz.TAZ
import beam.cosim.helics.BeamFederate
import beam.sim.config.BeamConfig
import org.matsim.api.core.v01.Id
import org.slf4j.{Logger, LoggerFactory}

import scala.util.control.NonFatal
import scala.util.{Failure, Try}

class PowerController(beamConfig: BeamConfig) {

  private val logger: Logger = LoggerFactory.getLogger(classOf[PowerController])
  private val cnmCfg = beamConfig.beam.agentsim.chargingNetworkManager

  private[power] lazy val beamFederateOption: Option[BeamFederate] = Try {
    logger.debug("Init PowerController resources...")
    BeamFederate.getFederateInstance(
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
      logger.error("Cannot init BeamFederate: {}", e.getMessage)
      Failure(e)
  }.toOption

  def initFederateConnection: Boolean = beamFederateOption.isDefined

  /**
    * Obtains physical bounds from the grid
    *
    * @param currentTime current time
    *  @param requiredLoad map required power per zone
    * @return tuple of PhysicalBounds and Int (next time)
    */
  def obtainPowerPhysicalBounds(
    currentTime: Int,
    requiredLoad: Map[ChargingZone, PowerInKW]
  ): Map[Int, PhysicalBounds] = {
    import SitePowerManager._
    val gridBounds = beamFederateOption match {
      case Some(beamFederate) =>
        logger.debug("Sending power over next planning horizon to the grid at time {}...", currentTime)
        beamFederate.publishJSON(
          requiredLoad
            .map(
              x =>
                Map(
                  "tazId"   -> x._1.tazId.toString,
                  "zoneId"  -> x._1.parkingZoneId,
                  "minLoad" -> x._2,
                  "maxLoad" -> x._2
              )
            )
            .toList
        )
        logger.debug("Obtaining power from the grid at time {}...", currentTime)
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
      case None =>
        logger.warn("Not connected to grid, nothing was sent")
        Map.empty[ZoneId, PhysicalBounds]
    }
    if (gridBounds.isEmpty)
      defaultPowerPhysicalBounds(currentTime, requiredLoad)
    else
      gridBounds
  }

  def close(): Unit = {
    logger.debug("Release PowerController resources...")
    beamFederateOption
      .fold(logger.warn("Not connected to grid, just releasing helics resources")) { beamFederate =>
        beamFederate.close()
      }

    try {
      logger.debug("Destroying BeamFederate")
      BeamFederate.unloadHelics()
    } catch {
      case NonFatal(ex) =>
        logger.error(s"Cannot destroy BeamFederate: ${ex.getMessage}")
    }
  }

  def defaultPowerPhysicalBounds(
    currentTime: Int,
    requiredLoad: Map[ChargingZone, PowerInKW]
  ): Map[ZoneId, PhysicalBounds] = {
    logger.warn("Not connected to grid, falling to default physical bounds at time {}...", currentTime)
    requiredLoad.map { case (k, v) => k.parkingZoneId -> PhysicalBounds(k.tazId, k.parkingZoneId, v, v) }
  }
}
