package beam.agentsim.infrastructure.power

import beam.agentsim.agents.vehicles.VehicleManager
import beam.agentsim.infrastructure.ChargingNetwork.ChargingStation
import beam.agentsim.infrastructure.ChargingNetworkManager.ChargingNetworkHelper
import beam.agentsim.infrastructure.parking.ParkingZone.createId
import beam.cosim.helics.BeamHelicsInterface._
import beam.sim.config.BeamConfig
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Try}

class PowerManager(chargingNetworkHelper: ChargingNetworkHelper, beamConfig: BeamConfig) extends LazyLogging {
  import PowerManager._
  private val pmcConfigMaybe = beamConfig.beam.agentsim.chargingNetworkManager.powerManagerController

  private[power] lazy val beamFederateOption: Option[BeamFederate] =
    pmcConfigMaybe match {
      case Some(pmcConfig) if pmcConfig.connect =>
        logger.warn("ChargingNetworkManager should connect to a power grid via Helics...")
        Try {
          logger.info("Init PowerManager Federate...")
          val fedInfo = createFedInfo(
            pmcConfig.coreType,
            s"--federates=1 --broker_address=${pmcConfig.brokerAddress}",
            pmcConfig.timeDeltaProperty,
            pmcConfig.intLogLevel
          )
          getFederate(
            pmcConfig.beamFederateName,
            fedInfo,
            pmcConfig.bufferSize,
            beamConfig.beam.agentsim.chargingNetworkManager.timeStepInSeconds,
            pmcConfig.beamFederatePublication match {
              case s: String if s.nonEmpty => Some(s)
              case _                       => None
            },
            (
              pmcConfig.pmcFederateName,
              pmcConfig.pmcFederateSubscription,
              pmcConfig.feedbackEnabled
            ) match {
              case (s1: String, s2: String, feedback: Boolean) if s1.nonEmpty && s2.nonEmpty && feedback =>
                Some(s1 + "/" + s2)
              case _ => None
            }
          )
        }.map { federate =>
          logger.info("Initialized federate, now it is going to execution mode")
          enterExecutionMode(10.seconds, federate)
          logger.info("Entered execution mode")
          federate
        }.recoverWith { case e =>
          logger.warn("Cannot init BeamFederate: {}. ChargingNetworkManager is not connected to the grid", e.getMessage)
          Failure(e)
        }.toOption
      case _ => None
    }

  /**
    * Obtains physical bounds from the grid
    *
    * @param currentTime current time
    *  @param estimatedLoad map required power per zone
    * @return tuple of PhysicalBounds and Int (next time)
    */
  def obtainPowerPhysicalBounds(
    currentTime: Int,
    estimatedLoad: Map[ChargingStation, PowerInKW]
  ): Map[ChargingStation, PowerInKW] = {
    beamFederateOption
      .map { beamFederate =>
        val messageToSend = estimatedLoad.map { case (station, powerInKW) =>
          // Sending this message
          Map(
            "reservedFor"   -> station.zone.reservedFor,
            "parkingZoneId" -> station.zone.parkingZoneId,
            "estimatedLoad" -> powerInKW
          )
        }.toList
        val messageReceived = beamFederate.cosimulate(currentTime, messageToSend)
        messageReceived.flatMap { message =>
          // Receiving this message
          val reservedFor = message("reservedFor").toString match {
            case managerIdString if managerIdString.isEmpty => VehicleManager.AnyManager
            case managerIdString =>
              VehicleManager.createOrGetReservedFor(managerIdString, Some(beamConfig)).get
          }
          chargingNetworkHelper
            .get(reservedFor)
            .lookupStation(createId(message("parkingZoneId").toString)) match {
            case Some(station) =>
              Some(station -> message("power_limit_upper").asInstanceOf[PowerInKW])
            case _ =>
              logger.error(
                "Cannot find the charging station correspondent to what has been received from the co-simulation"
              )
              None
          }
        }.toMap
      }
      .getOrElse {
        logger.debug("Not connected to grid, falling to default physical bounds at time {}...", currentTime)
        Map.empty[ChargingStation, PowerInKW]
      }
  }

  /**
    * closing helics connection
    */
  def close(): Unit = {
    beamFederateOption.fold(logger.debug("Not connected to grid, just releasing helics resources"))(_.close())
  }
}

object PowerManager {
  type PowerInKW = Double
  type EnergyInJoules = Double
  type ChargingDurationInSec = Int
}
