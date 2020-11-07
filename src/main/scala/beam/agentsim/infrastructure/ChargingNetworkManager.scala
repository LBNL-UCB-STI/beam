package beam.agentsim.infrastructure

import java.util.concurrent.TimeUnit

import akka.actor.Status.Failure
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
import scala.util.Random

/**
  * Created by haitamlaarabi
  */

class ChargingNetworkManager(
  beamServices: BeamServices,
  parkingManager: ActorRef,
  scheduler: ActorRef
) extends Actor
    with ActorLogging {
  import ChargingNetworkManager._
  import ChargingStatus._
  import beamServices._

  private val beamConfig: BeamConfig = beamScenario.beamConfig
  private val cnmConfig = beamConfig.beam.agentsim.chargingNetworkManager

  private val chargingNetworkMap = TrieMap.empty[String, ChargingNetwork]
  private val chargingZoneList: QuadTree[ChargingZone] = loadChargingZones(beamServices)
  private lazy val sitePowerManager = new SitePowerManager(chargingNetworkMap, beamServices)
  private lazy val powerController = new PowerController(chargingNetworkMap, beamConfig)
  private val endOfSimulationTime: Int = DateUtils.getEndOfTime(beamConfig)
  private def currentTimeBin(tick: Int): Int = cnmConfig.timeStepInSeconds * (tick / cnmConfig.timeStepInSeconds)
  private def nextTimeBin(tick: Int): Int = currentTimeBin(tick) + cnmConfig.timeStepInSeconds

  override def receive: Receive = {
    case TriggerWithId(InitializeTrigger(_), triggerId) =>
      import scala.concurrent.{ExecutionContext, Future}
      implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)
      implicit val executionContext: ExecutionContext = context.dispatcher
      chargingNetworkMap.put(defaultVehicleManager, new ChargingNetwork(defaultVehicleManager, chargingZoneList))
      Future(scheduler ? ScheduleTrigger(PlanningTimeOutTrigger(0), self))
        .map(_ => CompletionNotice(triggerId, Vector()))
        .pipeTo(sender())

    case TriggerWithId(PlanningTimeOutTrigger(timeBin), triggerId) =>
      log.debug(s"Planning energy dispatch for vehicles currently connected to a charging point, at t=$timeBin")
      import scala.concurrent.ExecutionContext.Implicits.global
      import scala.concurrent.duration._
      import scala.concurrent.{Await, Future, TimeoutException}
      val estimatedLoad = sitePowerManager.requiredPowerInKWOverNextPlanningHorizon(timeBin)
      log.debug("Total Load estimated is {} at tick {}", estimatedLoad.values.sum, timeBin)

      // obtaining physical bounds
      val physicalBounds = powerController.obtainPowerPhysicalBounds(timeBin, Some(estimatedLoad))

      val futures = Future
        .sequence(chargingNetworkMap.flatMap {
          case (vehicleManager, chargingNetwork) =>
            chargingNetwork.lookupConnectedVehicles.map {
              case chargingVehicle @ ChargingVehicle(vehicle, _, _, _, _, latestChargingSession, _) =>
                Future {
                  // Refuel
                  handleRefueling(chargingVehicle)
                  // Calculate the energy to charge each vehicle connected to the a charging station
                  val (chargingDuration, energyToCharge) =
                    sitePowerManager
                      .dispatchEnergy(timeBin, cnmConfig.timeStepInSeconds, chargingVehicle, physicalBounds)
                  // update charging vehicle with dispatched energy and schedule ChargingTimeOutScheduleTrigger
                  chargingNetwork
                    .combine(vehicle.id, ChargingSession(timeBin, energyToCharge, chargingDuration)) match {
                    case _ if chargingDuration < cnmConfig.timeStepInSeconds =>
                      chargingNetwork.disconnectVehicle(vehicle.id) match {
                        case Some((_: ChargingVehicle, _ @Disconnected)) =>
                          log.debug(s"Charging timeout for vehicle $vehicle at $timeBin in $chargingDuration seconds")
                          if (chargingDuration == 0) {
                            handleEndCharging(timeBin, vehicle.id, vehicleManager)
                            None
                          } else {
                            val endTime = (timeBin + chargingDuration).toInt
                            Some(ScheduleTrigger(ChargingTimeOutTrigger(endTime, vehicle.id, vehicleManager), self))
                          }
                        case _ =>
                          log.debug(s"Vehicle $vehicle is already disconnected at $timeBin")
                          None
                      }
                    case _ =>
                      log.debug(
                        "Ending refuel cycle for vehicle {}. Provided energy of {} J. Remaining {} J",
                        vehicle.id,
                        latestChargingSession.energy,
                        energyToCharge
                      )
                      None
                  }
                }
            }
        })
        .map(_.toVector)
        .recover {
          case e =>
            log.warning(s"Charging Replan did not produce allocations: $e")
            Vector.empty[Option[ScheduleTrigger]]
        }

      val triggers = (try {
        Await.result(futures, atMost = 1.minutes)
      } catch {
        case e: TimeoutException =>
          log.error(s"Timeout of Charging Replan with no allocations made: $e")
          Vector.empty[Option[ScheduleTrigger]]
      }).flatten

      // rescheduling the PlanningTimeOutTrigger
      sender ! CompletionNotice(
        triggerId,
        if (nextTimeBin(timeBin) <= endOfSimulationTime) {
          triggers ++ Vector(ScheduleTrigger(PlanningTimeOutTrigger(nextTimeBin(timeBin)), self))
        } else {
          // if we still have a BEV/PHEV that is connected to a charging point,
          // we assume that they will charge until the end of the simulation and throwing events accordingly
          triggers ++ chargingNetworkMap
            .flatMap(_._2.lookupConnectedVehicles)
            .map {
              case ChargingVehicle(vehicle, vehicleManager, _, _, _, lastChargingSession, _) =>
                val endTime = (timeBin + lastChargingSession.duration).toInt
                ScheduleTrigger(ChargingTimeOutTrigger(endTime, vehicle.id, vehicleManager), self)
            }
        }
      )

    case TriggerWithId(ChargingTimeOutTrigger(tick, vehicleId, vehicleManager), triggerId) =>
      log.debug(s"ChargingTimeOutTrigger for vehicle $vehicleId at $tick")
      handleEndCharging(tick, vehicleId, vehicleManager)
      sender ! CompletionNotice(triggerId)

    case ChargingPlugRequest(tick, vehicle, stall, vehicleManager) =>
      log.debug(s"ChargingPlugRequest for vehicle $vehicle at $tick")
      if (vehicle.isBEV | vehicle.isPHEV) {
        val chargingNetwork = chargingNetworkMap(vehicleManager)
        import chargingNetwork._
        log.debug(s"Agent ${sender.path.name} sent charging request for vehicle $vehicle at stall $stall")
        connectVehicle(tick, vehicle, stall, sender).foreach {
          case (chargingVehicle @ ChargingVehicle(_, _, _, _, _, _, theSender), _ @Connected) =>
            handleStartCharging(tick, chargingVehicle)
          case (ChargingVehicle(_, _, _, _, _, _, theSender), _) =>
            theSender ! WaitingInLine(tick, vehicle.id)
        }
      } else {
        sender ! Failure(
          new RuntimeException(
            s"$vehicle is not a BEV/PHEV vehicle. Request sent by agent ${sender.path.name} at stall $stall"
          )
        )
      }

    case ChargingUnplugRequest(tick, vehicle, vehicleManager) =>
      log.debug(s"ChargingUnplugRequest for vehicle $vehicle at $tick")
      // Obtain physical bounds
      val physicalBounds = powerController.obtainPowerPhysicalBounds(tick, None)
      // Calculate the energy to charge the vehicle
      val chargingNetwork = chargingNetworkMap(vehicleManager)
      // disconnect
      chargingNetwork
        .lookupVehicle(vehicle.id)
        .map {
          case chargingVehicle @ ChargingVehicle(_, _, _, _, _, ChargingSession(startTime, _, _), _) =>
            val realSTime = Math.min(tick, startTime)
            val realDuration = Math.abs(tick - startTime)
            val (chargeDurationAtTick, energyToChargeAtTick) =
              sitePowerManager.dispatchEnergy(realSTime, realDuration, chargingVehicle, physicalBounds)
            chargingNetwork.update(vehicle.id, ChargingSession(realSTime, energyToChargeAtTick, chargeDurationAtTick))
            chargingNetwork.disconnectVehicle(vehicle.id) match {
              case Some((_: ChargingVehicle, _ @Disconnected)) =>
                log.debug(s"Vehicle $vehicle was disconnected upon request at $tick")
              case _ =>
                log.debug(s"Vehicle $vehicle was found already disconnected at $tick")
            }
        }
        .getOrElse(None)
      // stop handling the vehicle
      if (handleEndCharging(tick, vehicle.id, vehicleManager)) {
        sender ! EndingRefuelSession(tick, vehicle.id)
      } else {
        sender ! UnhandledVehicle(tick, vehicle.id)
      }

    case Finish =>
      chargingNetworkMap.foreach(_._2.clear())
      chargingNetworkMap.clear()
      powerController.close()
      context.children.foreach(_ ! Finish)
      context.stop(self)
  }

  /**
    * Connect the vehicle
    * @param tick current time
    * @param chargingVehicle the vehicleManager name
    * @return vehicle charging information
    */
  private def handleStartCharging(tick: Int, chargingVehicle: ChargingVehicle): ChargingVehicle = {
    val physicalBounds = powerController.obtainPowerPhysicalBounds(tick, None)
    val ChargingVehicle(vehicle, vehicleManager, stall, _, _, _, _) = chargingVehicle
    val chargingNetwork = chargingNetworkMap(vehicleManager)
    vehicle.connectToChargingPoint(tick)
    chargingVehicle.theSender ! StartingRefuelSession(tick, vehicle.id)
    log.debug(s"vehicle $vehicle is connected to charging point at stall $stall")
    handleStartChargingHelper(tick, chargingVehicle)
    val (chargeDurationAtTick, energyToChargeAtTick) =
      sitePowerManager.dispatchEnergy(tick, nextTimeBin(tick) - tick, chargingVehicle, physicalBounds)
    chargingNetwork.combine(vehicle.id, ChargingSession(tick, energyToChargeAtTick, chargeDurationAtTick))
  }

  /**
    * Disconnect the vehicle
    * @param tick current time
    * @param vehicleId id of vehicle to disconnect
    * @param vehicleManager the vehicleManager name
    * @return False if vehicle is not handled anymore by CNM
    */
  private def handleEndCharging(tick: Int, vehicleId: Id[BeamVehicle], vehicleManager: VehicleManager): Boolean = {
    val chargingNetwork = chargingNetworkMap(vehicleManager)
    import chargingNetwork._
    removeDisconnectedVehicle(vehicleId) match {
      case Some(chargingVehicle @ ChargingVehicle(vehicle, _, _, chargingStation, _, _, _)) =>
        handleRefueling(chargingVehicle)
        handleEndChargingHelper(tick, chargingVehicle)
        vehicle.disconnectFromChargingPoint()
        // handle release parking stall
        vehicle.stall match {
          case Some(stall) =>
            parkingManager ! ReleaseParkingStall(stall.parkingZoneId, stall.tazId)
            vehicle.unsetParkingStall()
          case None =>
            log.error(s"Vehicle $vehicle has no stall while ending charging event")
        }
        processWaitingLine(chargingStation).foreach(y => handleStartCharging(tick, y._1))
        true
      case _ =>
        false
    }
  }

  /**
    * Refuel the vehicle using last charging session and collect the corresponding load
    * @param chargingVehicle vehicle charging information
    */
  private def handleRefueling(chargingVehicle: ChargingVehicle): Unit = {
    import chargingVehicle._
    vehicle.addFuel(latestChargingSession.energy)
    sitePowerManager.collectObservedLoadInKW(chargingVehicle, latestChargingSession)
    log.debug(s"Charging vehicle $vehicle. Provided energy of = ${latestChargingSession.energy} J")
  }

  /**
    * process the event ChargingPlugInEvent
    * @param currentTick current time
    * @param chargingVehicle vehicle charging information
    */
  private def handleStartChargingHelper(currentTick: Int, chargingVehicle: ChargingVehicle): Unit = {
    import chargingVehicle._
    val chargingPlugInEvent = new ChargingPlugInEvent(
      tick = currentTick,
      stall = stall,
      locationWGS = geo.utm2Wgs(stall.locationUTM),
      vehId = vehicle.id,
      primaryFuelLevel = vehicle.primaryFuelLevelInJoules,
      secondaryFuelLevel = Some(vehicle.secondaryFuelLevelInJoules)
    )
    log.debug(s"ChargingPlugInEvent: $chargingPlugInEvent")
    beamServices.matsimServices.getEvents.processEvent(chargingPlugInEvent)
  }

  /**
    * Calculates the duration of the refuel session, the provided energy and throws corresponding events
    * @param currentTick current time
    * @param chargingVehicle vehicle charging information
    */
  def handleEndChargingHelper(currentTick: Int, chargingVehicle: ChargingVehicle): Unit = {
    import chargingVehicle._
    log.debug(
      s"Vehicle $vehicle was disconnected at time {} with {} J delivered. Total of {} J during {} sec",
      currentTick,
      latestChargingSession.energy,
      totalChargingSession.energy,
      totalChargingSession.duration
    )
    // Refuel Session
    val refuelSessionEvent = new RefuelSessionEvent(
      currentTick,
      stall.copy(locationUTM = geo.utm2Wgs(stall.locationUTM)),
      totalChargingSession.energy,
      vehicle.primaryFuelLevelInJoules - totalChargingSession.energy,
      totalChargingSession.duration,
      vehicle.id,
      vehicle.beamVehicleType
    )
    log.debug(s"RefuelSessionEvent: $refuelSessionEvent")
    beamServices.matsimServices.getEvents.processEvent(refuelSessionEvent)

    // ChargingPlugOutEvent
    val chargingPlugOutEvent: ChargingPlugOutEvent = new ChargingPlugOutEvent(
      currentTick,
      stall.copy(locationUTM = geo.utm2Wgs(stall.locationUTM)),
      vehicle.id,
      vehicle.primaryFuelLevelInJoules,
      Some(vehicle.secondaryFuelLevelInJoules)
    )
    log.debug(s"ChargingPlugOutEvent: $chargingPlugOutEvent")
    beamServices.matsimServices.getEvents.processEvent(chargingPlugOutEvent)
  }

}

object ChargingNetworkManager {
  case class PlanningTimeOutTrigger(tick: Int) extends Trigger
  case class ChargingTimeOutTrigger(tick: Int, vehicleId: Id[BeamVehicle], vehicleManager: VehicleManager)
      extends Trigger
  case class ChargingPlugRequest(tick: Int, vehicle: BeamVehicle, stall: ParkingStall, vehicleManager: VehicleManager)
  case class ChargingUnplugRequest(tick: Int, vehicle: BeamVehicle, vehicleManager: VehicleManager)
  case class StartingRefuelSession(tick: Int, vehicleId: Id[BeamVehicle])
  case class EndingRefuelSession(tick: Int, vehicleId: Id[BeamVehicle])
  case class WaitingInLine(tick: Int, vehicleId: Id[BeamVehicle])
  case class UnhandledVehicle(tick: Int, vehicleId: Id[BeamVehicle])

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

    /**
      * Construct charging zone from the parking stall
      * @param stall ParkingStall
      * @param vehicleManager VehicleManager
      * @return ChargingZone
      */
    def to(stall: ParkingStall, vehicleManager: VehicleManager): ChargingZone = ChargingZone(
      stall.parkingZoneId,
      stall.tazId,
      stall.parkingType,
      1,
      stall.chargingPointType.get,
      stall.pricingModel.get,
      vehicleManager
    )

    /**
      * convert to ChargingZone from Map
      * @param charging Map of String keys and Any values
      * @return ChargingZone
      */
    def to(charging: Map[String, Any]): ChargingZone = {
      ChargingZone(
        charging("chargingZoneId").asInstanceOf[Int],
        Id.create(charging("tazId").asInstanceOf[String], classOf[TAZ]),
        ParkingType(charging("parkingType").asInstanceOf[String]),
        charging("numChargers").asInstanceOf[Int],
        ChargingPointType(charging("chargingPointType").asInstanceOf[String]).get,
        PricingModel(
          charging("pricingModel").asInstanceOf[String],
          charging("costInDollars").asInstanceOf[Double].toString
        ).get,
        charging("vehicleManager").asInstanceOf[String]
      )
    }

    /**
      * Convert chargingZone to a Map
      * @param chargingZone ChargingZone
      * @return Map of String keys and Any values
      */
    def from(chargingZone: ChargingZone): Map[String, Any] = {
      Map(
        "chargingZoneId"    -> chargingZone.chargingZoneId,
        "tazId"             -> chargingZone.tazId.toString,
        "parkingType"       -> chargingZone.parkingType.toString,
        "numChargers"       -> chargingZone.numChargers,
        "chargingPointType" -> chargingZone.chargingPointType.toString,
        "pricingModel"      -> chargingZone.pricingModel.toString,
        "vehicleManager"    -> chargingZone.vehicleManager
      )
    }
  }

  /**
    * load parking stalls with charging point
    * @param beamServices BeamServices
    * @return QuadTree of ChargingZone
    */
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
