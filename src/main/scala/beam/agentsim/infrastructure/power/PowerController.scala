package beam.agentsim.infrastructure.power

import beam.cosim.helics.BeamFederate
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import org.slf4j.{Logger, LoggerFactory}

import scala.util.{Failure, Try}

class PowerController(beamServices: BeamServices, beamConfig: BeamConfig) {

  private val logger: Logger = LoggerFactory.getLogger(classOf[PowerController])

  logger.info("Init PowerController resources...")
  private val beamFederateMaybe: Try[BeamFederate] = Try {
    BeamFederate.loadHelics
    BeamFederate.getInstance(beamServices)
  }.recoverWith {
    case e =>
      logger.error("Cannot init BeamFederate: {}", e.getMessage)
      Failure(e)
  }
  private val isConnectedToHelics: Boolean = beamFederateMaybe.map(_.isFederateValid).getOrElse(false)

  def publishPowerOverPlanningHorizon(power: Double): Unit = {
    beamFederateMaybe
      .map(_.publishPowerOverPlanningHorizon(power))
      .recoverWith {
        case e =>
          logger.error("Cannot publish power over planning horizon: {}", e.getMessage)
          Failure(e)
      }
      .getOrElse(())
  }

  /**
    *
    * @param requiredPower power (in joules) over planning horizon
    * @param tick
    * @return PhysicalBounds, Int (next tick)
    */
  def calculatePower(requiredPower: Double, tick: Int): (PhysicalBounds, Int) = {
    logger.info("Calculating required power {} (isConnectedToHelics: {})...", requiredPower, isConnectedToHelics)
    val (nextTick, powerValue) =
      beamFederateMaybe
        .map(_.syncAndGetPowerValue(tick))
        .recoverWith {
          case e =>
            logger.error("Cannot calculate power: {}", e.getMessage)
            Failure(e)
        }
        .getOrElse { (beamConfig.beam.cosim.helics.timeStep * 4, 0.0) } // TODO should be a parameter, timeInterval etc.
    (PhysicalBounds.default(powerValue), nextTick)
  }

  def close(): Unit = {
    logger.info("Release PowerController resources (isConnectedToHelics: {})...", isConnectedToHelics)
    beamFederateMaybe.foreach(_.close())
    BeamFederate.destroyInstance()
  }
}
