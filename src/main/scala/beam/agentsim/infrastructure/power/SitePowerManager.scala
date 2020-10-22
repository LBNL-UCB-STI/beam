package beam.agentsim.infrastructure.power

import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.infrastructure.ChargingNetworkManager.ChargingZone
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.skim.TAZSkims
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import spray.json.{DefaultJsonProtocol, DeserializationException, JsNumber, JsObject, JsString, JsValue, RootJsonFormat}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future, TimeoutException}
import scala.concurrent.duration._

class SitePowerManager(chargingStations: Map[Int, ChargingZone], planningHorizonInSec: Int, tazSkimmer: TAZSkims)
    extends LazyLogging {
  import SitePowerManager._

  private val unlimitedPhysicalBounds: Map[ZoneId, PhysicalBounds] =
    chargingStations.map(
      s =>
        s._1 -> PhysicalBounds(
          s._2.tazId,
          s._2.parkingZoneId,
          ChargingPointType.getChargingPointInstalledPowerInKw(s._2.chargingPointType) * s._2.maxStations,
          ChargingPointType.getChargingPointInstalledPowerInKw(s._2.chargingPointType) * s._2.maxStations
      )
    )

  private var physicalBoundsInternal: Map[ZoneId, PhysicalBounds] = unlimitedPhysicalBounds

  /**
    *
    * @return Physical bounds
    */
  private def physicalBounds: Map[ZoneId, PhysicalBounds] = physicalBoundsInternal

  /**
    * Set physical bounds
    *
    * @param physicalBounds Physical bounds from the Power Controller
    */
  def updatePhysicalBounds(physicalBounds: Map[ZoneId, PhysicalBounds]): Unit = physicalBoundsInternal = physicalBounds

  /**
    * Get required power for electrical vehicles
    *
    * @param tick bean time
    * @return power (in Kilo Watt) over planning horizon
    */
  def getPowerOverNextPlanningHorizon(tick: Int): Map[ChargingZone, PowerInKW] = {
    if (!tazSkimmer.isEmpty()) {
      chargingStations.map {
        case (_, s) =>
          val load = tazSkimmer.getLatestSkimByTAZ(tick, s.tazId, SKIM_ACTOR, SKIM_VAR_PREFIX + s.parkingZoneId) match {
            case Some(skim) => skim.value * skim.observations
            case None       => 0.0
          }
          s -> load
      }
    } else {
      chargingStations.map { case (_, s) => s -> unlimitedPhysicalBounds(s.parkingZoneId).maxLoad }
    }
  }

  /**
    * Replan horizon per electrical vehicles
    *
    * @param vehicles beam vehicles
    * @param chargingSessionInSec duration of charging
    * @return a future of map of electrical vehicles with required amount of energy in joules
    */
  private def replanHorizonAndGetChargingPlanPerVehicleHelper(
    vehicles: Iterable[BeamVehicle],
    chargingSessionInSec: Int
  ): Future[Map[Id[BeamVehicle], (ChargingDurationInSec, EnergyInJoules, EnergyInJoules)]] = {
    val timeInterval = Math.min(planningHorizonInSec, chargingSessionInSec)
    Future
      .sequence(
        vehicles.map { v =>
          Future {
            val stall = v.stall.get
            val maxZoneLoad = physicalBounds(stall.parkingZoneId).maxLoad
            val maxUnlimitedZoneLoad = unlimitedPhysicalBounds(stall.parkingZoneId).maxLoad
            val chargingPointLoad = ChargingPointType.getChargingPointInstalledPowerInKw(stall.chargingPointType.get)
            val chargingPowerLimit = maxZoneLoad * chargingPointLoad / maxUnlimitedZoneLoad
            val (chargingDuration, energyToCharge) =
              v.refuelingSessionDurationAndEnergyInJoules(Some(timeInterval), Some(chargingPowerLimit))
            val (_, unconstrainedEnergy) = v.refuelingSessionDurationAndEnergyInJoules(Some(timeInterval))
            v.id -> (chargingDuration, energyToCharge, unconstrainedEnergy)
          }
        }
      )
      .map(result => result.toMap)
      .recover {
        case e =>
          logger.warn(s"Charging Replan did not produce allocations: $e")
          Map.empty[Id[BeamVehicle], (ChargingDurationInSec, EnergyInJoules, EnergyInJoules)]
      }
  }

  /**
    * Wait for futureChargingReplanPerVehicleBis to terminate before returning the charging plans per vehicle
    *
    * @param vehicles beam vehicles
    * @param chargingSessionInSec duration of charging
    * @return map of electrical vehicles with required amount of energy in joules
    */
  def replanHorizonAndGetChargingPlanPerVehicle(
    vehicles: Iterable[BeamVehicle],
    chargingSessionInSec: Int
  ): Map[Id[BeamVehicle], (ChargingDurationInSec, EnergyInJoules, EnergyInJoules)] = {
    try {
      Await.result(replanHorizonAndGetChargingPlanPerVehicleHelper(vehicles, chargingSessionInSec), atMost = 1.minutes)
    } catch {
      case e: TimeoutException =>
        logger.error(s"timeout of Charging Replan with no allocations made: $e")
        Map.empty[Id[BeamVehicle], (ChargingDurationInSec, EnergyInJoules, EnergyInJoules)]
    }
  }

  /**
    * reset physical bounds
    */
  def resetState(): Unit = {
    physicalBoundsInternal = unlimitedPhysicalBounds
  }
}

object SitePowerManager {
  val SKIM_ACTOR = "SitePowerManager"
  val SKIM_VAR_PREFIX = "ChargingStation-"

  type PowerInKW = Double
  type EnergyInJoules = Double
  type ChargingDurationInSec = Long
  type ZoneId = Int

  case class PhysicalBounds(tazId: Id[TAZ], zoneId: ZoneId, minLoad: PowerInKW, maxLoad: PowerInKW)

  object JsonProtocol extends DefaultJsonProtocol {
    implicit object PBMJsonFormat extends RootJsonFormat[PhysicalBounds] {

      def write(c: PhysicalBounds): JsValue = JsObject(
        "tazId"   -> JsString(c.tazId.toString),
        "zoneId"  -> JsNumber(c.zoneId),
        "minLoad" -> JsNumber(c.minLoad),
        "maxLoad" -> JsNumber(c.maxLoad)
      )

      def read(value: JsValue): PhysicalBounds = {
        value.asJsObject.getFields("tazId", "zoneId", "minLoad", "maxLoad") match {
          case Seq(JsString(tazId), JsNumber(zoneId), JsNumber(minLoad), JsNumber(maxLoad)) =>
            PhysicalBounds(Id.create(tazId, classOf[TAZ]), zoneId.toInt, minLoad.toDouble, maxLoad.toDouble)
          case _ =>
            throw DeserializationException("PhysicalBounds expected")
        }
      }
    }
  }
}
