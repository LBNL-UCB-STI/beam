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

class PowerController(
  chargingNetworkMap: Map[Id[VehicleManager], ChargingNetwork],
  beamConfig: BeamConfig,
  beamFederateOption: Option[BeamFederate]
) extends LazyLogging {
  import SitePowerManager._

  private val cnmConfig = beamConfig.beam.agentsim.chargingNetworkManager
  private var physicalBounds = Map.empty[ChargingStation, PhysicalBounds]
  private val chargingStationsMap = chargingNetworkMap.flatMap(_._2.chargingStations).map(s => s.zone -> s).toMap
  private val unlimitedPhysicalBounds = getUnlimitedPhysicalBounds(chargingStationsMap.values.toList.distinct).value
  private var currentBin = -1

  /**
    * Obtains physical bounds from the grid
    *
    * @param currentTime current time
    *  @param estimatedLoadMaybe map required power per zone
    * @return tuple of PhysicalBounds and Int (next time)
    */
  def obtainPowerPhysicalBounds(
    currentTime: Int,
    estimatedLoadMaybe: Option[Map[ChargingStation, PowerInKW]] = None
  ): Map[ChargingStation, PhysicalBounds] = {
    physicalBounds = beamFederateOption match {
      case Some(beamFederate)
          if cnmConfig.helics.connectionEnabled && estimatedLoadMaybe.isDefined && (physicalBounds.isEmpty || currentBin < currentTime / cnmConfig.timeStepInSeconds) =>
        logger.debug("Sending power over next planning horizon to the grid at time {}...", currentTime)
        val msgToPublish = estimatedLoadMaybe match {
          case Some(estimatedLoad) =>
            val msg = estimatedLoad.map {
              case (station, powerInKW) =>
                Map(
                  "managerId"         -> station.zone.managerId,
                  "tazId"             -> station.zone.tazId.toString,
                  "parkingType"       -> station.zone.parkingType.toString,
                  "chargingPointType" -> station.zone.chargingPointType.toString,
                  "numChargers"       -> station.zone.numChargers,
                  "estimatedLoad"     -> powerInKW
                )
            }.toList
            msg
          case _ => List.empty[Map[String, Any]]
        }
        // PUBLISH
        beamFederate.publishJSON(msgToPublish)
        // SYNC 1
        beamFederate.sync(currentTime)
        // SYNC 2 then Collect
        val gridBounds = beamFederate.syncThenCollectJSON(currentTime + 1)
        // PROCESS
        gridBounds.flatMap { x =>
          val managerId = Id.create(x("managerId").asInstanceOf[String], classOf[VehicleManager])
          val chargingNetwork = chargingNetworkMap(managerId)
          chargingNetwork.lookupStation(
            Id.create(x("tazId").asInstanceOf[String], classOf[TAZ]),
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
}
