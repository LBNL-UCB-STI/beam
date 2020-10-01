package beam.agentsim.infrastructure.power

import beam.agentsim.infrastructure.ChargingNetworkManager.ChargingZone
import beam.agentsim.infrastructure.power.SitePowerManager.{PhysicalBounds, PowerInKW, ZoneId}
import beam.cosim.helics.BeamFederate
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import org.slf4j.{Logger, LoggerFactory}
import spray.json._

import scala.util.control.NonFatal
import scala.util.{Failure, Try}

class PowerController(beamServices: BeamServices, beamConfig: BeamConfig) {

  private val logger: Logger = LoggerFactory.getLogger(classOf[PowerController])

  private[power] lazy val beamFederateOption: Option[BeamFederate] = Try {
    logger.debug("Init PowerController resources...")
    BeamFederate.loadHelics
    BeamFederate.getInstance(
      beamServices,
      beamConfig.beam.agentsim.chargingNetworkManager.helicsFederateName,
      beamConfig.beam.agentsim.chargingNetworkManager.planningHorizonInSeconds
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
    import DefaultJsonProtocol._
    import SitePowerManager._
    import JsonProtocol.PBMJsonFormat
    logger.debug("Sending power over next planning horizon to the grid at time {}...", currentTime)
    beamFederateOption
      .fold(logger.warn("Not connected to grid, nothing was sent")) { beamFederate =>
        val value =
          requiredLoad.map(x => PhysicalBounds(x._1.tazId, x._1.parkingZoneId, x._2, x._2)).toList
        beamFederate.publishPowerOverPlanningHorizon(value.toJson)
      }
    logger.debug("Obtaining power from the grid at time {}...", currentTime)
    val bounds = beamFederateOption
      .map { beamFederate =>
        beamFederate.syncAndMoveToNextTimeStep(currentTime)
        beamFederate.obtainPowerFlowValue
          .convertTo[List[PhysicalBounds]]
          .map(x => x.zoneId -> x)
      }
      .getOrElse(defaultPowerPhysicalBounds(currentTime, requiredLoad))
      .toMap
    logger.debug("Obtained power from the grid {}...", bounds)
    bounds
  }

  def close(): Unit = {
    logger.debug("Release PowerController resources...")
    beamFederateOption
      .fold(logger.warn("Not connected to grid, just releasing helics resources")) { beamFederate =>
        beamFederate.close()
      }

    try {
      logger.debug("Destroying BeamFederate")
      BeamFederate.destroyInstance()
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
