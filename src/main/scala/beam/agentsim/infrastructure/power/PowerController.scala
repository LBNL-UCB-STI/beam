package beam.agentsim.infrastructure.power

import beam.cosim.helics.BeamFederate
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import org.slf4j.{Logger, LoggerFactory}

import scala.util.{Failure, Try}

class PowerController(beamServices: BeamServices, beamConfig: BeamConfig) {

  private val logger: Logger = LoggerFactory.getLogger(classOf[PowerController])

  logger.debug("Init PowerController resources...")
  private val beamFederateMaybe: Option[BeamFederate] = Try {
    BeamFederate.loadHelics
    BeamFederate.getInstance(beamServices)
  }.recoverWith {
    case e =>
      logger.error("Cannot init BeamFederate: {}", e.getMessage)
      Failure(e)
  }.toOption

  def publishPowerOverPlanningHorizon(power: Double): Unit = {
    beamFederateMaybe
      .map(_.publishPowerOverPlanningHorizon(power))
      .getOrElse {
        logger.warn("Not connected to Grid, nothing was sent")
      }
  }

  /**
    *
    * @param requiredPower power (in joules) over planning horizon
    * @param tick current tick
    * @return tuple of PhysicalBounds and Int (next tick)
    */
  def calculatePower(requiredPower: Double, tick: Int): (PhysicalBounds, Int) = {
    logger.debug("Calculating required power {} at time {}...", requiredPower, tick)
    val (nextTick, powerValue) =
      beamFederateMaybe
        .map(_.syncAndGetPowerFlowValue(tick))
        .getOrElse {
          logger.warn("Not connected to Grid, using default physical bounds (0.0)")
          val fedTimeStep = beamConfig.beam.cosim.helics.timeStep
          (fedTimeStep * (1 + (tick / fedTimeStep)), 0.0)
        }
    logger.debug("Calculated required power {}...", powerValue)
    (PhysicalBounds.default(powerValue), nextTick)
  }

  def close(): Unit = {
    logger.debug("Release PowerController resources...")
    beamFederateMaybe
      .map(_.close())
      .getOrElse {
        logger.warn("Not connected to Grid, just releasing helics resources")
      }
    BeamFederate.destroyInstance()
  }
}
