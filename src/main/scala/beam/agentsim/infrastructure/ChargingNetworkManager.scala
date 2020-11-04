package beam.agentsim.infrastructure

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.events.{ChargingPlugInEvent, ChargingPlugOutEvent, RefuelSessionEvent}
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.{ParkingType, PricingModel}
import beam.agentsim.infrastructure.power.{PowerController, SitePowerManager}
import beam.agentsim.infrastructure.taz.TAZ
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.BeamRouter.Location
import beam.sim.config.BeamConfig
import beam.sim.{BeamScenario, BeamServices}
import beam.utils.DateUtils
import org.matsim.api.core.v01.Id
import org.matsim.core.utils.collections.QuadTree

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

class ChargingNetworkManager(
  beamServices: BeamServices,
  beamScenario: BeamScenario,
  parkingManager: ActorRef,
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
  private val sitePowerManager = new SitePowerManager(chargingStationsMap, beamServices)
  private val powerController = new PowerController(beamConfig)
  private val endOfSimulationTime: Int = DateUtils.getEndOfTime(beamConfig)

  override def receive: Receive = {
    case TriggerWithId(InitializeTrigger(_), triggerId) =>
      Future(scheduler ? ScheduleTrigger(PlanningTimeOutTrigger(0), self))
        .map(_ => CompletionNotice(triggerId, Vector()))
        .pipeTo(sender())

    case TriggerWithId(PlanningTimeOutTrigger(tick), triggerId) =>
      log.debug(s"Planning energy dispatch for vehicles currently connected to a charging point, at t=$tick")
      val estimatedLoad = sitePowerManager.getPowerOverNextPlanningHorizon(tick)
      log.debug("Total Load estimated is {} at tick {}", estimatedLoad.values.sum, tick)
      val physicalBounds = powerController.obtainPowerPhysicalBounds(tick, chargingStationsMap, Some(estimatedLoad))

      // Calculate the energy to charge each vehicle connected to the a charging station
      val triggers = sitePowerManager
        .replanHorizonAndGetChargingPlanPerVehicle(tick, vehicles.values, physicalBounds, cnmConfig.timeStepInSeconds)
        .flatMap {
          case (vehicleId, (chargingDuration, constrainedEnergyToCharge)) =>
            // Get vehicle charging status
            val ChargingVehicle(vehicle, totalChargingSession, _) = vehiclesToCharge(vehicleId)

            // Refuel the vehicle
            val currentSession = ChargingSession(constrainedEnergyToCharge, chargingDuration)
            log.debug("Charging vehicle {}. Energy to charge = {}", vehicle, currentSession.energy)
            vehicle.addFuel(currentSession.energy)

            // Verify the state of charge and schedule UnplugTimeOutTrigger
            val updatedChargingVehicle =
              ChargingVehicle(vehicle, totalChargingSession.combine(currentSession), currentSession)
            vehiclesToCharge.update(vehicle.id, updatedChargingVehicle)

            //  Verify the state of charge and schedule ChargingTimeOutScheduleTrigger
            vehicle.refuelingSessionDurationAndEnergyInJoules() match {
              case (remainingDuration, _) if chargingDuration == 0 && remainingDuration == 0 =>
                unplugVehicle(tick, vehicleId)
                None
              case (remainingDuration, remainingEnergy) if remainingDuration > 0 =>
                log.debug(
                  "Ending refuel cycle for vehicle {}. Provided {} J. remaining {} J for {} sec",
                  vehicle.id,
                  currentSession.energy,
                  remainingEnergy,
                  remainingDuration
                )
                None
              case (remainingDuration, _) if remainingDuration == 0 =>
                Some(chargingTimeOutScheduleTrigger(tick, updatedChargingVehicle))
              case (remainingDuration, _) =>
                log.error(
                  s"Something is widely broken for vehicle ${vehicle.id} at tick $tick with remaining charging duration $remainingDuration and current charging duration of $chargingDuration"
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
          val completeTriggers = triggers ++ vehiclesToCharge.map(vc => chargingTimeOutScheduleTrigger(tick, vc._2))
          vehiclesToCharge.clear()
          completeTriggers
        }
      )

    case TriggerWithId(ChargingTimeOutTrigger(tick, vehicleId), triggerId) =>
      unplugVehicle(tick, vehicleId)
      sender ! CompletionNotice(triggerId)

    case ChargingPlugRequest(tick, vehicle, agent) =>
      val result = if (vehicle.isBEV | vehicle.isPHEV) {
        log.debug(
          "ChargingPlugRequest for vehicle {} by agent {} on stall {}",
          vehicle,
          agent.path.name,
          vehicle.stall
        )
        vehiclesToCharge.put(
          vehicle.id,
          ChargingVehicle(
            vehicle,
            totalChargingSession = ChargingSession.Empty,
            lastChargingSession = ChargingSession.Empty
          )
        )
        handleStartCharging(tick, vehicle)
        Some(vehicle.id)
      } else {
        log.error(
          "ChargingPlugRequest for non BEV/PHEV vehicle {} by agent {} on stall {}",
          vehicle,
          agent.path.name,
          vehicle.stall
        )
        None
      }
      sender ! StartRefuelSession(tick, result)

    case ChargingUnplugRequest(tick, vehicle, _) =>
      log.debug("ChargingUnplugRequest for vehicle {} at {}", vehicle, tick)
      val chargingVehicles = vehicles.values.toList
      val physicalBounds = powerController.obtainPowerPhysicalBounds(tick, chargingStationsMap, None)
      val result = unplugVehicle(
        tick,
        vehicle.id,
        Some((chargingVehicle: ChargingVehicle) => {
          // Calculate the energy to charge the vehicle
          val (chargeDurationAtTick, constrainedEnergyToCharge) =
            sitePowerManager.replanHorizonAndGetChargingPlanPerVehicle(
              tick,
              chargingVehicles,
              physicalBounds,
              tick % cnmConfig.timeStepInSeconds
            )(vehicle.id)

          // Refuel the vehicle
          val currentSession = ChargingSession(constrainedEnergyToCharge, chargeDurationAtTick)
          log.debug("Charging vehicle {}. Energy to charge = {}", vehicle, currentSession.energy)
          vehicle.addFuel(currentSession.energy)
          // Preparing EndRefuelSessionTrigger to notify the driver
          ChargingVehicle(
            vehicle,
            chargingVehicle.totalChargingSession.combine(currentSession),
            currentSession
          )
        })
      )
      sender ! EndRefuelSessionUponRequest(tick, result)

    case Finish =>
      powerController.close()
      context.children.foreach(_ ! Finish)
      context.stop(self)
  }

  private def unplugVehicle(
    tick: Int,
    vehicleId: Id[BeamVehicle],
    callbackMaybe: Option[ChargingVehicle => ChargingVehicle] = None
  ): Option[Id[BeamVehicle]] = {
    vehiclesToCharge.remove(vehicleId) match {
      case Some(chargingVehicle) =>
        val ChargingVehicle(vehicle, totalChargingSession, _) =
          callbackMaybe.map(_(chargingVehicle)).getOrElse(chargingVehicle)
        log.debug(
          "Vehicle {} has been unplugged. Scheduling EndRefuelSessionTrigger at {} with {} J delivered",
          vehicle,
          tick,
          totalChargingSession.energy
        )
        handleEndCharging(tick, vehicle, totalChargingSession.duration, totalChargingSession.energy)
        Some(vehicle.id)
      case _ =>
        log.debug(
          "At tick {} the vehicle {} cannot be Unplugged. Either the vehicle had already been unplugged or something is widely wrong",
          tick,
          vehicleId
        )
        None
    }
  }

  /**
    * process the event ChargingPlugInEvent
    * @param currentTick current time
    * @param vehicle vehicle to be charged
    */
  private def handleStartCharging(currentTick: Int, vehicle: BeamVehicle): Unit = {
    log.debug("Starting refuel session for {} in tick {}.", vehicle.id, currentTick)
    log.debug("Vehicle {} connects to charger @ stall {}", vehicle.id, vehicle.stall.get)
    vehicle.connectToChargingPoint(currentTick)
    val chargingPlugInEvent = new ChargingPlugInEvent(
      tick = currentTick,
      stall = vehicle.stall.get,
      locationWGS = geo.utm2Wgs(vehicle.stall.get.locationUTM),
      vehId = vehicle.id,
      primaryFuelLevel = vehicle.primaryFuelLevelInJoules,
      secondaryFuelLevel = Some(vehicle.secondaryFuelLevelInJoules)
    )
    beamServices.matsimServices.getEvents.processEvent(chargingPlugInEvent)
  }

  /**
    * Calculates the duration of the refuel session, the provided energy and throws corresponding events
    * @param currentTick current time
    * @param vehicle vehicle to end charging
    * @param chargingDuration the duration of charging session
    * @param energyInJoules the energy in joules
    */
  def handleEndCharging(
    currentTick: Int,
    vehicle: BeamVehicle,
    chargingDuration: Long,
    energyInJoules: Double
  ): Unit = {
    log.debug(
      "Ending refuel session for {} in tick {}. Provided {} J. during {}",
      vehicle.id,
      currentTick,
      energyInJoules,
      chargingDuration
    )
    val refuelSessionEvent = new RefuelSessionEvent(
      currentTick,
      vehicle.stall.get.copy(locationUTM = geo.utm2Wgs(vehicle.stall.get.locationUTM)),
      energyInJoules,
      vehicle.primaryFuelLevelInJoules - energyInJoules,
      chargingDuration,
      vehicle.id,
      vehicle.beamVehicleType
    )
    log.debug(s"RefuelSessionEvent: $refuelSessionEvent")
    beamServices.matsimServices.getEvents.processEvent(refuelSessionEvent)
    vehicle.disconnectFromChargingPoint()
    log.debug(
      "Vehicle {} disconnected from charger @ stall {}",
      vehicle.id,
      vehicle.stall.get
    )
    val chargingPlugOutEvent: ChargingPlugOutEvent = new ChargingPlugOutEvent(
      currentTick,
      vehicle.stall.get
        .copy(locationUTM = geo.utm2Wgs(vehicle.stall.get.locationUTM)),
      vehicle.id,
      vehicle.primaryFuelLevelInJoules,
      Some(vehicle.secondaryFuelLevelInJoules)
    )
    beamServices.matsimServices.getEvents.processEvent(chargingPlugOutEvent)
    vehicle.stall match {
      case Some(stall) =>
        parkingManager ! ReleaseParkingStall(stall.parkingZoneId, stall.tazId)
        vehicle.unsetParkingStall()
      case None =>
        log.error("Vehicle has no stall while ending charging event")
    }
  }

  private def chargingTimeOutScheduleTrigger(tick: Int, chargingVehicle: ChargingVehicle): ScheduleTrigger = {
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
}

object ChargingNetworkManager {
  import cats.Eval
  final case class PlanningTimeOutTrigger(tick: Int) extends Trigger
  final case class ChargingTimeOutTrigger(tick: Int, vehicleId: Id[BeamVehicle]) extends Trigger
  final case class ChargingPlugRequest(tick: Int, vehicle: BeamVehicle, drivingAgent: ActorRef)
  final case class ChargingUnplugRequest(tick: Int, vehicle: BeamVehicle, drivingAgent: ActorRef)
  final case class EndRefuelSessionUponRequest(tick: Int, vehicleMaybe: Option[Id[BeamVehicle]])
  final case class StartRefuelSession(tick: Int, vehicleMaybe: Option[Id[BeamVehicle]])

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
    totalChargingSession: ChargingSession,
    lastChargingSession: ChargingSession
  )

  final case class ChargingNetwork(fleetManager: FleetManagerID, chargingStationsQTree: QuadTree[ChargingZone]) {

    val chargingStationsMap: Map[Int, ChargingZone] =
      chargingStationsQTree.values().asScala.map(s => s.parkingZoneId -> s).toMap
    private val vehiclesToCharge: TrieMap[Id[BeamVehicle], ChargingVehicle] = new TrieMap()
    def vehicles: Map[Id[BeamVehicle], BeamVehicle] = vehiclesToCharge.mapValues(_.vehicle).toMap

  }

  object ChargingSession {
    val Empty: ChargingSession = ChargingSession(0.0, 0)
  }

}
