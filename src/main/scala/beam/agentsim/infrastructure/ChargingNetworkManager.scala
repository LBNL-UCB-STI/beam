package beam.agentsim.infrastructure

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.agents.modalbehaviors.DrivesVehicle._
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.{ParkingType, PricingModel}
import beam.agentsim.infrastructure.power.{PowerController, SitePowerManager}
import beam.agentsim.infrastructure.taz.TAZ
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.BeamRouter.Location
import beam.router.skim.TAZSkimmerEvent
import beam.sim.config.BeamConfig
import beam.sim.{BeamScenario, BeamServices}
import beam.utils.DateUtils
import org.matsim.api.core.v01.Id
import org.matsim.core.utils.collections.QuadTree

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

class ChargingNetworkManager(
  beamServices: BeamServices,
  beamScenario: BeamScenario,
  scheduler: ActorRef
) extends Actor
    with ActorLogging {
  import ChargingNetworkManager._
  import beamServices._

  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)
  private implicit val executionContext: ExecutionContext = context.dispatcher

  private val beamConfig: BeamConfig = beamScenario.beamConfig
  private val cnmConfig = beamConfig.beam.agentsim.chargingNetworkManager
  private val vehiclesToCharge: TrieMap[Id[BeamVehicle], ChargingVehicle] = new TrieMap()
  private def vehicles: Map[Id[BeamVehicle], BeamVehicle] = vehiclesToCharge.mapValues(_.vehicle).toMap

  private val chargingStationsQTree: QuadTree[ChargingZone] = loadChargingStations()
  private val chargingStationsMap: Map[Int, ChargingZone] =
    chargingStationsQTree.values().asScala.map(s => s.parkingZoneId -> s).toMap
  private val sitePowerManager =
    new SitePowerManager(
      chargingStationsMap,
      beamServices.beamConfig.beam.agentsim.chargingNetworkManager.timeStepInSeconds,
      beamServices.skims.taz_skimmer
    )
  private val powerController = new PowerController(beamConfig)
  private val endOfSimulationTime: Int = DateUtils.getEndOfTime(beamConfig)

  override def receive: Receive = {
    case TriggerWithId(InitializeTrigger(_), triggerId) =>
      Future(scheduler ? ScheduleTrigger(PlanningTimeOutTrigger(0), self))
        .map(_ => CompletionNotice(triggerId, Vector()))
        .pipeTo(sender())

    case TriggerWithId(PlanningTimeOutTrigger(tick), triggerId) =>
      log.debug("PlanningTimeOutTrigger, tick: {}", tick)

      val estimatedLoad = sitePowerManager.getPowerOverNextPlanningHorizon(tick)
      log.debug("Total Load estimated is {} at tick {}", estimatedLoad.values.sum, tick)
      val physicalBounds = powerController.obtainPowerPhysicalBounds(tick, chargingStationsMap, Some(estimatedLoad))

      // Calculate the energy to charge each vehicle connected to the a charging station
      val triggers = sitePowerManager
        .replanHorizonAndGetChargingPlanPerVehicle(vehicles.values, physicalBounds, cnmConfig.timeStepInSeconds)
        .flatMap {
          case (vehicleId, (chargingDuration, constrainedEnergyToCharge, unconstrainedEnergy)) =>
            // Get vehicle charging status
            val ChargingVehicle(vehicle, agent, totalChargingSession, _) = vehiclesToCharge(vehicleId)

            // Collect data on load demand
            collectDataOnLoadDemand(tick, vehicle.stall.get, chargingDuration, unconstrainedEnergy)

            // Refuel the vehicle
            val currentSession = ChargingSession(constrainedEnergyToCharge, chargingDuration)
            log.debug("Charging vehicle {}. Energy to charge = {}", vehicle, currentSession.energy)
            vehicle.addFuel(currentSession.energy)

            // Verify the state of charge and schedule UnplugTimeOutTrigger
            val updatedChargingVehicle =
              ChargingVehicle(vehicle, agent, totalChargingSession.combine(currentSession), currentSession)
            vehiclesToCharge.update(vehicle.id, updatedChargingVehicle)

            val (remainingDuration, remainingEnergy) = vehicle.refuelingSessionDurationAndEnergyInJoules()
            if (chargingDuration == 0) {
              unplugVehicle(tick, vehicleId)
            } else if (chargingDuration <= cnmConfig.timeStepInSeconds && remainingDuration == 0) {
              Some(getChargingTimeOutScheduleTrigger(tick, updatedChargingVehicle))
            } else {
              log.debug(
                "Ending refuel cycle for vehicle {}. Provided {} J. remaining {} J for {} sec",
                vehicle.id,
                currentSession.energy,
                remainingEnergy,
                remainingDuration
              )
              None
            }
        }
        .toVector

      // rescheduling the PlanningTimeOutTrigger
      val nextTick = cnmConfig.timeStepInSeconds * (1 + (tick / cnmConfig.timeStepInSeconds))
      sender ! CompletionNotice(
        triggerId,
        if (nextTick <= endOfSimulationTime) {
          triggers ++ Vector(ScheduleTrigger(PlanningTimeOutTrigger(nextTick), self))
        } else {
          // if we still have a BEV/PHEV that is connected to a charging point,
          // we assume that they will charge until the end of the simulation and throwing events accordingly
          val completeTriggers = triggers ++ vehiclesToCharge.map(vc => getChargingTimeOutScheduleTrigger(tick, vc._2))
          vehiclesToCharge.clear()
          completeTriggers
        }
      )

    case ChargingUnplugRequest(vehicle, tick) =>
      log.info("ChargingUnplugRequest for vehicle {} at {}", vehicle, tick)
      val chargingVehicles = vehicles.values.toList
      val physicalBounds = powerController.obtainPowerPhysicalBounds(tick, chargingStationsMap, None)
      vehiclesToCharge.remove(vehicle.id) match {
        case Some(ChargingVehicle(vehicle, agent, totalChargingSession, _)) =>
          // Calculate Energy to Charge
          val (chargeDurationAtTick, constrainedEnergyToCharge, unconstrainedEnergy) =
            sitePowerManager.replanHorizonAndGetChargingPlanPerVehicle(
              chargingVehicles,
              physicalBounds,
              tick % cnmConfig.timeStepInSeconds
            )(vehicle.id)

          // Collect data on unconstrained load demand (not the constrained demand)
          collectDataOnLoadDemand(tick, vehicle.stall.get, chargeDurationAtTick, unconstrainedEnergy)

          // Refuel the vehicle
          val currentSession = ChargingSession(constrainedEnergyToCharge, chargeDurationAtTick)
          log.debug("Charging vehicle {}. Energy to charge = {}", vehicle, currentSession.energy)
          vehicle.addFuel(currentSession.energy)

          // Preparing EndRefuelSessionTrigger to notify the driver
          val updatedChargingVehicle =
            ChargingVehicle(vehicle, agent, totalChargingSession.combine(currentSession), currentSession)
          scheduler ! getEndRefuelSessionScheduleTrigger(tick, updatedChargingVehicle)

        case _ =>
          log.warning(
            "Either ChargingUnplugRequest occurred after the end charging or ChargingUnplugRequest is wrong for vehicle {}",
            vehicle.id
          )
      }

    case TriggerWithId(ChargingTimeOutTrigger(tick, vehicleId), triggerId) =>
      sender ! CompletionNotice(triggerId, unplugVehicle(tick, vehicleId) match {
        case Some(trigger) => Vector(trigger)
        case _             => Vector()
      })

    case ChargingPlugRequest(vehicle, drivingAgent) =>
      plugVehicle(vehicle, drivingAgent)
  }

  private def collectDataOnLoadDemand(
    tick: Int,
    stall: ParkingStall,
    chargingDuration: Long,
    requiredEnergy: Double
  ): Unit = {
    // Collect data on load demand
    val currentBin = cnmConfig.timeStepInSeconds * (tick / cnmConfig.timeStepInSeconds)
    beamServices.matsimServices.getEvents.processEvent(
      TAZSkimmerEvent(
        currentBin,
        stall.locationUTM,
        SKIM_VAR_PREFIX + stall.parkingZoneId,
        if (chargingDuration == 0) 0.0 else (requiredEnergy / 3.6e+6) / (chargingDuration / 3600.0),
        beamServices,
        SKIM_ACTOR
      )
    )
  }

  private def plugVehicle(vehicle: BeamVehicle, drivingAgent: ActorRef): Unit = {
    if (vehicle.isBEV | vehicle.isPHEV) {
      log.info(
        "ChargingPlugRequest for vehicle {} by agent {} on stall {}",
        vehicle,
        drivingAgent.path.name,
        vehicle.stall
      )
      vehiclesToCharge.put(
        vehicle.id,
        ChargingVehicle(
          vehicle,
          drivingAgent,
          totalChargingSession = ChargingSession.Empty,
          lastChargingSession = ChargingSession.Empty
        )
      )
    } else {
      log.error(
        "ChargingPlugRequest for non BEV/PHEV vehicle {} by agent {} on stall {}",
        vehicle,
        drivingAgent.path.name,
        vehicle.stall
      )
    }
  }

  private def unplugVehicle(tick: Int, vehicleId: Id[BeamVehicle]): Option[ScheduleTrigger] = {
    vehiclesToCharge.remove(vehicleId) match {
      case Some(chargingVehicle) => Some(getEndRefuelSessionScheduleTrigger(tick, chargingVehicle))
      case _ =>
        log.debug(
          "At tick {} the vehicle {} cannot be Unplugged. Either the vehicle had already been unplugged or something is widely wrong",
          tick,
          vehicleId
        )
        None
    }
  }

  private def getEndRefuelSessionScheduleTrigger(tick: Int, chargingVehicle: ChargingVehicle): ScheduleTrigger = {
    log.debug(
      "Vehicle {} has been unplugged. Scheduling EndRefuelSessionTrigger at {} with {} J delivered",
      chargingVehicle.vehicle,
      tick,
      chargingVehicle.totalChargingSession.energy
    )
    ScheduleTrigger(
      EndRefuelSessionTrigger(
        tick,
        chargingVehicle.vehicle.getChargerConnectedTick(),
        chargingVehicle.totalChargingSession.energy,
        chargingVehicle.vehicle
      ),
      chargingVehicle.agent
    )
  }

  private def getChargingTimeOutScheduleTrigger(tick: Int, chargingVehicle: ChargingVehicle): ScheduleTrigger = {
    val nextTick = tick + chargingVehicle.lastChargingSession.duration.toInt
    log.debug(
      "Vehicle {} is going to be fully charged during this time bin. Scheduling ChargingTimeOutTrigger at {} to deliver {} J delivered",
      chargingVehicle.vehicle.id,
      nextTick,
      chargingVehicle.totalChargingSession.energy
    )
    ScheduleTrigger(
      ChargingTimeOutTrigger(nextTick, chargingVehicle.vehicle.id),
      self
    )
  }

  private def loadChargingStations(): QuadTree[ChargingZone] = {
    val (zones, _) = ZonalParkingManager.loadParkingZones(
      beamConfig.beam.agentsim.taz.parkingFilePath,
      beamConfig.beam.agentsim.taz.filePath,
      beamConfig.beam.agentsim.taz.parkingStallCountScalingFactor,
      beamConfig.beam.agentsim.taz.parkingCostScalingFactor,
      new Random(beamConfig.matsim.modules.global.randomSeed)
    )
    val zonesWithCharger = zones.filter(_.chargingPointType.isDefined)
    val coordinates = zonesWithCharger.flatMap(z => beamScenario.tazTreeMap.getTAZ(z.tazId)).map(_.coord)
    val xs = coordinates.map(_.getX)
    val ys = coordinates.map(_.getY)
    val envelopeInUTM = geo.wgs2Utm(beamScenario.transportNetwork.streetLayer.envelope)
    envelopeInUTM.expandBy(beamConfig.beam.spatial.boundingBoxBuffer)
    envelopeInUTM.expandToInclude(xs.min, ys.min)
    envelopeInUTM.expandToInclude(xs.max, ys.max)

    val stationsQuadTree = new QuadTree[ChargingZone](
      envelopeInUTM.getMinX,
      envelopeInUTM.getMinY,
      envelopeInUTM.getMaxX,
      envelopeInUTM.getMaxY
    )
    zones.filter(_.chargingPointType.isDefined).foreach { zone =>
      beamScenario.tazTreeMap.getTAZ(zone.tazId) match {
        case Some(taz) =>
          stationsQuadTree.put(
            taz.coord.getX,
            taz.coord.getY,
            ChargingZone(
              zone.parkingZoneId,
              zone.tazId,
              zone.parkingType,
              zone.stallsAvailable,
              zone.maxStalls,
              zone.chargingPointType.get,
              zone.pricingModel.get
            )
          )
        case _ =>
      }
    }
    stationsQuadTree
  }

  override def postStop: Unit = {
    log.info("postStop")
    if (cnmConfig.gridConnectionEnabled) {
      powerController.close()
    }
    super.postStop()
  }
}

object ChargingNetworkManager {
  import cats.Eval
  final case class PlanningTimeOutTrigger(tick: Int) extends Trigger
  final case class ChargingTimeOutTrigger(tick: Int, vehicleId: Id[BeamVehicle]) extends Trigger

  type PowerInKW = Double
  type EnergyInJoules = Double
  type ChargingDurationInSec = Long
  type ZoneId = Int

  case class PhysicalBounds(tazId: Id[TAZ], zoneId: ZoneId, minLoad: PowerInKW, maxLoad: PowerInKW)

  def unlimitedPhysicalBounds(stations: Map[Int, ChargingZone]): Eval[Map[ZoneId, PhysicalBounds]] = {
    Eval.later {
      stations
        .map(
          s =>
            s._2.parkingZoneId -> PhysicalBounds(
              s._2.tazId,
              s._2.parkingZoneId,
              ChargingPointType.getChargingPointInstalledPowerInKw(s._2.chargingPointType) * s._2.maxStations,
              ChargingPointType.getChargingPointInstalledPowerInKw(s._2.chargingPointType) * s._2.maxStations
          )
        )
        .toMap
    }
  }

  val SKIM_ACTOR = "ChargingNetworkManager"
  val SKIM_VAR_PREFIX = "ChargingStation-"

  final case class ChargingSession(energy: Double, duration: Long) {

    def combine(other: ChargingSession): ChargingSession = ChargingSession(
      energy = this.energy + other.energy,
      duration = this.duration + other.duration
    )
  }
  final case class ChargingZone(
    parkingZoneId: Int,
    tazId: Id[TAZ],
    parkingType: ParkingType,
    stationsAvailable: Int,
    maxStations: Int,
    chargingPointType: ChargingPointType,
    pricingModel: PricingModel
  )
  final case class ChargingStation(zone: ChargingZone, locationUTM: Location, costInDollars: Double)
  final case class ChargingVehicle(
    vehicle: BeamVehicle,
    agent: ActorRef,
    totalChargingSession: ChargingSession,
    lastChargingSession: ChargingSession
  )

  object ChargingSession {
    val Empty: ChargingSession = ChargingSession(0.0, 0)
  }

}
