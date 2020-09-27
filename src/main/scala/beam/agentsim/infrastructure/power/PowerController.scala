package beam.agentsim.infrastructure.power

import beam.agentsim.infrastructure.ChargingNetworkManager.ChargingZone
import beam.agentsim.infrastructure.power.SitePowerManager.PhysicalBounds
import beam.cosim.helics.BeamFederate
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import org.slf4j.{Logger, LoggerFactory}

import scala.util.control.NonFatal
import scala.util.{Failure, Try}
import spray.json._
import DefaultJsonProtocol._

class PowerController(beamServices: BeamServices, beamConfig: BeamConfig) {
  import PowerController._
  import JsonProtocol._
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
    * @return tuple of PhysicalBounds and Int (next time)
    */
  def obtainPowerPhysicalBounds(currentTime: Int, requiredLoad: Map[ChargingZone, Double]): Map[Int, PhysicalBounds] = {
    logger.debug("Sending power over next planning horizon to the grid at time {}...", currentTime)
    beamFederateOption
      .fold(logger.warn("Not connected to grid, nothing was sent")) { beamFederate =>
        val value =
          requiredLoad.map(x => PhysicalBoundsMessage(x._1.tazId.toString, x._1.parkingZoneId, x._2, x._2).toJson)
        beamFederate.publishPowerOverPlanningHorizon(JsArray(value.toVector))
      }
    logger.debug("Obtaining power from the grid at time {}...", currentTime)
    val bounds = beamFederateOption
      .map { beamFederate =>
        beamFederate.syncAndMoveToNextTimeStep(currentTime)
        beamFederate.obtainPowerFlowValue
          .convertTo[List[PhysicalBoundsMessage]]
          .map(x => x.zoneId -> PhysicalBounds(x.minLoad, x.maxLoad))
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
    requiredLoad: Map[ChargingZone, Double]
  ): Map[Int, PhysicalBounds] = {
    logger.warn("Not connected to grid, falling to default physical bounds at time {}...", currentTime)
    requiredLoad.map { case (k, v) => k.parkingZoneId -> PhysicalBounds(v, v) }
  }
}

object PowerController {
  case class PhysicalBoundsMessage(tazId: String, zoneId: Int, minLoad: Double, maxLoad: Double)

  object JsonProtocol extends DefaultJsonProtocol {
    implicit object PBMJsonFormat extends RootJsonFormat[PhysicalBoundsMessage] {

      def write(c: PhysicalBoundsMessage) = JsObject(
        "tazId"   -> JsString(c.tazId),
        "zoneId"  -> JsNumber(c.zoneId),
        "minLoad" -> JsNumber(c.minLoad),
        "maxLoad" -> JsNumber(c.maxLoad)
      )

      def read(value: JsValue) = {
        value.asJsObject.getFields("tazId", "zoneId", "minLoad", "maxLoad") match {
          case Seq(JsString(tazId), JsNumber(zoneId), JsNumber(minLoad), JsNumber(maxLoad)) =>
            PhysicalBoundsMessage(tazId, zoneId.toInt, minLoad.toDouble, maxLoad.toDouble)
          case _ =>
            throw DeserializationException("PhysicalBoundsMessage expected")
        }
      }
    }
  }
}
