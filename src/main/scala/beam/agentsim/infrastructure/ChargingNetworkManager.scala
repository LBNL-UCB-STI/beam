package beam.agentsim.infrastructure

import java.util.concurrent.TimeUnit

import akka.actor.Status.{Failure, Success}
import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.events.{ChargingPlugInEvent, ChargingPlugOutEvent, RefuelSessionEvent}
import beam.agentsim.infrastructure.ChargingNetwork.{ChargingSession, ChargingStatus, ChargingVehicle}
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.{ParkingType, PricingModel}
import beam.agentsim.infrastructure.power.{PowerController, SitePowerManager}
import beam.agentsim.infrastructure.taz.TAZ
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import beam.utils.DateUtils
import org.matsim.api.core.v01.Id
import org.matsim.core.utils.collections.QuadTree

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

class ChargingNetworkManager(
  beamServices: BeamServices,
  parkingManager: ActorRef,
  scheduler: ActorRef
) extends Actor
    with ActorLogging {
  import ChargingNetworkManager._
  import ChargingStatus._
  import beamServices._

  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)
  private implicit val executionContext: ExecutionContext = context.dispatcher
  private val beamConfig: BeamConfig = beamScenario.beamConfig
  private val cnmConfig = beamConfig.beam.agentsim.chargingNetworkManager

  private val chargingNetworkMap = TrieMap.empty[String, ChargingNetwork]
  private val chargingZoneList: QuadTree[ChargingZone] = loadChargingZones(beamServices)
  private lazy val sitePowerManager = new SitePowerManager(chargingNetworkMap, beamServices)
  private lazy val powerController = new PowerController(chargingNetworkMap, beamConfig)
  private val endOfSimulationTime: Int = DateUtils.getEndOfTime(beamConfig)

  override def receive: Receive = {
    case TriggerWithId(InitializeTrigger(_), triggerId) =>
      chargingNetworkMap.put(defaultVehicleManager, new ChargingNetwork(defaultVehicleManager, chargingZoneList))
      Future(scheduler ? ScheduleTrigger(PlanningTimeOutTrigger(0), self))
        .map(_ => CompletionNotice(triggerId, Vector()))
        .pipeTo(sender())

    case TriggerWithId(PlanningTimeOutTrigger(tick), triggerId) =>
      log.debug(s"Planning energy dispatch for vehicles currently connected to a charging point, at t=$tick")
      val estimatedLoad = sitePowerManager.getPowerOverNextPlanningHorizon(tick)
      log.debug("Total Load estimated is {} at tick {}", estimatedLoad.values.sum, tick)
      val physicalBounds = powerController.obtainPowerPhysicalBounds(tick, Some(estimatedLoad))

      // Calculate the energy to charge each vehicle connected to the a charging station
      val triggers = sitePowerManager
        .dispatchEnergy(tick, physicalBounds)
        .flatMap {
          case (vehicleId, (vehicleManager, chargingDuration, energyToCharge)) =>
            val chargingNetwork = chargingNetworkMap(vehicleManager)

            // lookup charging information
            val Some(ChargingVehicle(vehicle, _, _, _, _, _)) = chargingNetwork.lookupVehicle(vehicleId)

            // Refuel the vehicle
            val currentSession = ChargingSession(energyToCharge, chargingDuration)
            log.debug(s"Charging vehicle $vehicle. Energy to charge = ${currentSession.energy}")
            vehicle.addFuel(currentSession.energy)
            val updatedChargingVehicle = chargingNetwork.update(vehicleId, currentSession)

            //  Verify the state of charge and schedule ChargingTimeOutScheduleTrigger
            vehicle.refuelingSessionDurationAndEnergyInJoules() match {
              case (remainingDuration, _) if chargingDuration == 0 && remainingDuration == 0 =>
                chargingNetwork.disconnectVehicle(tick, vehicleId) match {
                  case Some((_, _ @Disconnected)) =>
                  case _                          =>
                }
                disconnectFromChargingPoint(tick, vehicleId, updatedChargingVehicle.vehicleManager)
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
                  s"Something is widely broken for vehicle {} at tick {} with remaining charging duration {} and current charging duration of {}",
                  vehicle.id,
                  tick,
                  remainingDuration,
                  chargingDuration
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
          val completeTriggers = triggers ++ chargingNetworkMap
            .flatMap(_._2.lookupAllVehicles())
            .map(vc => chargingTimeOutScheduleTrigger(tick, vc))
          chargingNetworkMap.foreach(_._2.clear())
          completeTriggers
        }
      )

    case TriggerWithId(ChargingTimeOutTrigger(tick, vehicle, vehicleManager), triggerId) =>
      disconnectFromChargingPoint(tick, vehicle.id, vehicleManager)
      sender ! CompletionNotice(triggerId)

    case ChargingPlugRequest(tick, vehicle, stall, vehicleManager) =>
      if (vehicle.isBEV | vehicle.isPHEV) {
        log.debug(s"agent ${sender.path.name} sent charging request for vehicle $vehicle at stall $stall")
        connectToChargingPoint(tick, vehicle, stall, vehicleManager)
        sender ! Success(s"vehicle $vehicle was added to the queue for charging")
      } else {
        log.error(s"$vehicle is not a BEV/PHEV vehicle. Request sent by agent ${sender.path.name} at stall $stall")
        sender ! Failure(new RuntimeException(s"vehicle $vehicle cannot be added to chargign queue"))
      }

    case ChargingUnplugRequest(tick, vehicle, vehicleManager) =>
      log.debug(s"ChargingUnplugRequest for vehicle $vehicle at $tick")
      val physicalBounds = powerController.obtainPowerPhysicalBounds(tick, None)
      // Calculate the energy to charge the vehicle
      val energyToChargeOption =
        sitePowerManager.dispatchEnergy(tick, physicalBounds, Some(tick % cnmConfig.timeStepInSeconds)).get(vehicle.id)
      val disconnectedVehicleOption = disconnectFromChargingPoint(
        tick,
        vehicle.id,
        vehicleManager,
        Some((chargingVehicle: ChargingVehicle) => {
          val Some((_, chargeDurationAtTick, energyToCharge)) = energyToChargeOption
          // Refuel the vehicle
          val currentSession = ChargingSession(energyToCharge, chargeDurationAtTick)
          log.debug(s"Charging vehicle $vehicle. Energy to charge = ${currentSession.energy}")
          vehicle.addFuel(currentSession.energy)
          // Preparing EndRefuelSessionTrigger to notify the driver
          chargingVehicle.copy(
            totalChargingSession = chargingVehicle.totalChargingSession.combine(currentSession),
            lastChargingSession = currentSession
          )
        })
      )
      sender ! EndRefuelSessionUponRequest(tick, disconnectedVehicleOption)

    case Finish =>
      chargingNetworkMap.clear()
      powerController.close()
      context.children.foreach(_ ! Finish)
      context.stop(self)
  }

  /**
    *
    * @param tick current time
    * @param vehicle BeamVehicle to connect to charging point
    * @param stall ParkingStall to which BeamVehicle is going to be assigned (supposed to be already reserved)
    * @param vehicleManager VehicleManager that is managing the BeamVehicle
    */
  private def connectToChargingPoint(
    tick: Int,
    vehicle: BeamVehicle,
    stall: ParkingStall,
    vehicleManager: VehicleManager
  ): Unit = {
    chargingNetworkMap(vehicleManager).connectVehicle(tick, vehicle, stall) match {
      case (_, _ @Connected) =>
        log.debug(s"vehicle ${vehicle.id} is connected to charging point at stall $stall")
        handleStartCharging(tick, vehicle)
      case _ =>
        log.debug(s"vehicle ${vehicle.id} is waiting in line for charging point at stall $stall")
    }
  }

  /**
    *
    * @param tick current time
    * @param vehicleId id of BeamVehicle to disconnect to charging point
    * @param vehicleManager VehicleManager that is managing the BeamVehicle
    * @param callbackMaybe callback function. It is used here to calculate energy required in case of unplug request
    * @return
    */
  private def disconnectFromChargingPoint(
    tick: Int,
    vehicleId: Id[BeamVehicle],
    vehicleManager: VehicleManager,
    callbackMaybe: Option[ChargingVehicle => ChargingVehicle] = None
  ): Option[Id[BeamVehicle]] = {
    chargingNetworkMap(vehicleManager).disconnectVehicle(tick, vehicleId) match {
      case Some((chargingVehicle @ ChargingVehicle(vehicle, _, _, _, totalChargingSession, _), _ @Disconnected)) =>
        callbackMaybe.map(_(chargingVehicle)).getOrElse(chargingVehicle)
        log.debug(s"Vehicle $vehicleId was disconnected at $tick with ${totalChargingSession.energy} J delivered")
        handleEndCharging(tick, vehicle, totalChargingSession.duration, totalChargingSession.energy)
        Some(vehicle.id)
      case _ =>
        log.debug(s"Vehicle $vehicleId cannot be disconnected at $tick. Either already disconnected or widely broken")
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
    ScheduleTrigger(ChargingTimeOutTrigger(nextTick, chargingVehicle.vehicle, chargingVehicle.vehicleManager), self)
  }

}

object ChargingNetworkManager {
  case class PlanningTimeOutTrigger(tick: Int) extends Trigger
  case class ChargingTimeOutTrigger(tick: Int, vehicle: BeamVehicle, vehicleManager: VehicleManager) extends Trigger
  case class ChargingPlugRequest(tick: Int, vehicle: BeamVehicle, stall: ParkingStall, vehicleManager: VehicleManager)
  case class ChargingUnplugRequest(tick: Int, vehicle: BeamVehicle, vehicleManager: VehicleManager)
  case class EndRefuelSessionUponRequest(tick: Int, vehicleMaybe: Option[Id[BeamVehicle]])

  type VehicleManager = String
  val defaultVehicleManager: VehicleManager = "INDIVIDUAL"

  final case class ChargingZone(
    chargingZoneId: Int,
    tazId: Id[TAZ],
    parkingType: ParkingType,
    numChargers: Int,
    chargingPointType: ChargingPointType,
    pricingModel: PricingModel,
    vehicleManager: VehicleManager
  ) {
    val uniqueId: String = s"($vehicleManager,$tazId,$chargingZoneId,$parkingType,$chargingPointType)"
    override def equals(that: Any): Boolean =
      that match {
        case that: ChargingZone =>
          that.hashCode() == hashCode
        case _ => false
      }
    override def hashCode: Int = uniqueId.hashCode()
  }

  object ChargingZone {

    def toChargingZoneInstance(stall: ParkingStall, vehicleManager: VehicleManager): ChargingZone = ChargingZone(
      stall.parkingZoneId,
      stall.tazId,
      stall.parkingType,
      1,
      stall.chargingPointType.get,
      stall.pricingModel.get,
      vehicleManager
    )

    def toChargingZoneInstance(x: Map[String, Any]): ChargingZone = {
      ChargingZone(
        x("chargingZoneId").asInstanceOf[Int],
        Id.create(x("tazId").asInstanceOf[String], classOf[TAZ]),
        ParkingType(x("parkingType").asInstanceOf[String]),
        x("numChargers").asInstanceOf[Int],
        ChargingPointType(x("chargingPointType").asInstanceOf[String]).get,
        PricingModel(x("pricingModel").asInstanceOf[String], x("costInDollars").asInstanceOf[Double].toString).get,
        x("vehicleManager").asInstanceOf[String]
      )
    }

    def toStringMap(x: ChargingZone): Map[String, Any] = {
      Map(
        "chargingZoneId"    -> x.chargingZoneId,
        "tazId"             -> x.tazId.toString,
        "parkingType"       -> x.parkingType.toString,
        "numChargers"       -> x.numChargers,
        "chargingPointType" -> x.chargingPointType.toString,
        "pricingModel"      -> x.pricingModel.toString,
        "vehicleManager"    -> x.vehicleManager
      )
    }
  }

  private def loadChargingZones(beamServices: BeamServices): QuadTree[ChargingZone] = {
    import beamServices._
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
              zone.maxStalls,
              zone.chargingPointType.get,
              zone.pricingModel.get,
              defaultVehicleManager
            )
          )
        case _ =>
      }
    }
    stationsQuadTree
  }
}
