package beam.agentsim.infrastructure

import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.events.{ChargingPlugInEvent, ChargingPlugOutEvent, RefuelSessionEvent}
import beam.agentsim.infrastructure.ChargingNetwork.ChargingStatus.Connected
import beam.agentsim.infrastructure.ChargingNetwork.{ChargingCycle, ChargingStation, ChargingVehicle}
import beam.agentsim.infrastructure.ChargingNetworkManager.{
  ChargingTimeOutTrigger,
  EndingRefuelSession,
  StartingRefuelSession
}
import beam.agentsim.infrastructure.power.PowerController.PhysicalBounds
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.sim.config.BeamConfig.Beam.Agentsim
import beam.utils.DateUtils
import org.matsim.api.core.v01.Id

import scala.collection.concurrent.TrieMap

trait ChargingNetworkManagerHelper extends {
  this: ChargingNetworkManager =>

  private lazy val endOfSimulationTime: Int = DateUtils.getEndOfTime(beamConfig)
  private lazy val cnmConfig: Agentsim.ChargingNetworkManager = beamConfig.beam.agentsim.chargingNetworkManager
  private lazy val parallelismWindow: Int = beamConfig.beam.agentsim.schedulerParallelismWindow
  protected val vehicle2InquiryMap: TrieMap[Id[BeamVehicle], ParkingInquiry] = TrieMap()

  /**
    * if this is the last timebin of the simulation
    * @param tick current tick
    * @return a boolean
    */
  protected def isEndOfSimulation(tick: Int): Boolean = nextTimeBin(tick) >= endOfSimulationTime

  /**
    * if charging completed then duration of charging should be zero
    * @param cycle the latest charging cycle
    * @return
    */
  protected def chargingIsCompleteUsing(cycle: ChargingCycle): Boolean = (cycle.endTime - cycle.startTime) == 0

  /**
    * if charging won't complete during the current cycle
    * @param cycle the latest charging cycle
    * @return
    */
  protected def chargingNotCompleteUsing(cycle: ChargingCycle): Boolean =
    (cycle.endTime - cycle.startTime) >= cycle.maxDuration

  /**
    * Get current time bin
    * @param tick time
    * @return
    */
  protected def currentTimeBin(tick: Int): Int = cnmConfig.timeStepInSeconds * (tick / cnmConfig.timeStepInSeconds)

  /**
    * get next time bin
    * @param tick time
    * @return
    */
  protected def nextTimeBin(tick: Int): Int = currentTimeBin(tick) + cnmConfig.timeStepInSeconds

  /**
    * @param chargingVehicle the vehicle being charged
    * @param startTime the start time
    * @param endTime the end time
    * @param physicalBounds physical bounds
    * @param triggerId trigger Id
    * @param interruptCharging True if the charging should be interrupted
    * @return
    */
  protected def dispatchEnergyAndProcessChargingCycle(
    chargingVehicle: ChargingVehicle,
    startTime: Int,
    endTime: Int,
    physicalBounds: Map[ChargingStation, PhysicalBounds],
    triggerId: Long,
    interruptCharging: Boolean
  ): Option[ScheduleTrigger] = {
    assume(endTime - startTime >= 0, s"timeInterval should not be negative! startTime $startTime endTime $endTime")
    // Calculate the energy to charge each vehicle connected to the a charging station
    val updatedEndTime = chargingVehicle.chargingShouldEndAt.map(_ - parallelismWindow).getOrElse(endTime)
    val duration = Math.max(0, updatedEndTime - startTime)
    val maxCycleDuration = Math.min(
      nextTimeBin(startTime) - startTime,
      chargingVehicle.chargingShouldEndAt.map(_ - parallelismWindow - startTime).getOrElse(Int.MaxValue)
    )
    chargingVehicle.checkAndCorrectCycleAfterInterruption(updatedEndTime)
    val (chargingDuration, energyToCharge, energyToChargeIfUnconstrained) =
      sitePowerManager.dispatchEnergy(duration, chargingVehicle, physicalBounds)
    val theActualEndTime = startTime + chargingDuration
    log.debug(
      s"dispatchEnergyAndProcessChargingCycle. startTime:$startTime, endTime:$endTime, updatedEndTime:$updatedEndTime, " +
      s"theActualEndTime:$theActualEndTime, duration:$duration, maxCycleDuration:$maxCycleDuration, " +
      s"chargingVehicle:$chargingVehicle, chargingDuration:$chargingDuration"
    )
    // update charging vehicle with dispatched energy and schedule ChargingTimeOutScheduleTrigger
    chargingVehicle
      .processCycle(startTime, theActualEndTime, energyToCharge, energyToChargeIfUnconstrained, maxCycleDuration)
      .flatMap {
        case cycle if chargingIsCompleteUsing(cycle) || interruptCharging =>
          handleEndCharging(cycle.endTime, chargingVehicle, triggerId, interruptCharging)
          None
        case cycle if chargingNotCompleteUsing(cycle) && !isEndOfSimulation(startTime) =>
          log.debug(
            s"Vehicle {} is still charging @ Stall: {}. Provided energy: {} J. Remaining: {} J",
            chargingVehicle.vehicle.id,
            chargingVehicle.stall,
            cycle.energyToCharge,
            energyToCharge
          )
          None
        case cycle =>
          // charging is going to end during this current session
          Some(ScheduleTrigger(ChargingTimeOutTrigger(cycle.endTime, chargingVehicle.vehicle), self))
      }
  }

  /**
    * Connect the vehicle
    * @param tick current time
    * @param chargingVehicle charging vehicle information
    */
  protected def handleStartCharging(
    tick: Int,
    chargingVehicle: ChargingVehicle,
    triggerId: Long
  ): Unit = {
    val nextTick = nextTimeBin(tick)
    val vehicle = chargingVehicle.vehicle
    if (vehicle.stall.isEmpty)
      vehicle.useParkingStall(chargingVehicle.stall)
    log.debug(s"Starting charging for vehicle $vehicle at $tick")
    val physicalBounds = powerController.obtainPowerPhysicalBounds(tick, None)
    chargingVehicle.theSender ! StartingRefuelSession(tick, triggerId)
    processStartChargingEvent(tick, chargingVehicle)
    dispatchEnergyAndProcessChargingCycle(
      chargingVehicle,
      tick,
      nextTick,
      physicalBounds,
      triggerId,
      interruptCharging = false
    ).foreach(
      getScheduler ! _
    )
  }

  /**
    * Disconnect the vehicle
    * @param tick current time
    * @param chargingVehicle charging vehicle information
    * @param triggerId the trigger
    * @param chargingInterrupted Boolean
    */
  protected def handleEndCharging(
    tick: Int,
    chargingVehicle: ChargingVehicle,
    triggerId: Long,
    chargingInterrupted: Boolean
  ): Unit = {
    val result = chargingVehicle.chargingStatus.last.status match {
      case Connected =>
        chargingVehicle.chargingStation.endCharging(chargingVehicle.vehicle.id, tick) orElse {
          log.debug(
            s"Vehicle ${chargingVehicle.vehicle.id} has already ended charging"
          )
          None
        }
      case _ => Some(chargingVehicle)
    }
    if (result.isDefined) {
      handleRefueling(chargingVehicle)
      processEndChargingEvents(tick, chargingVehicle)
      if (!chargingInterrupted)
        chargingVehicle.theSender ! EndingRefuelSession(tick, chargingVehicle.vehicle.id, triggerId)
    }
  }

  /**
    * Refuel the vehicle using last charging session and collect the corresponding load
    * @param chargingVehicle vehicle charging information
    */
  protected def handleRefueling(chargingVehicle: ChargingVehicle): Unit = {
    chargingVehicle.refuel.foreach { case ChargingCycle(startTime, endTime, _, energyToChargeIfUnconstrained, _) =>
      val station = chargingVehicle.chargingStation
      sitePowerManager.collectObservedLoadInKW(startTime, endTime - startTime, energyToChargeIfUnconstrained, station)
    }
  }

  /**
    * process the event ChargingPlugInEvent
    * @param currentTick current time
    * @param chargingVehicle vehicle charging information
    */
  private def processStartChargingEvent(currentTick: Int, chargingVehicle: ChargingVehicle): Unit = {
    import chargingVehicle._
    val chargingPlugInEvent = new ChargingPlugInEvent(
      tick = currentTick,
      stall = stall,
      locationWGS = getBeamServices.geo.utm2Wgs(stall.locationUTM),
      vehId = vehicle.id,
      primaryFuelLevel = vehicle.primaryFuelLevelInJoules,
      secondaryFuelLevel = Some(vehicle.secondaryFuelLevelInJoules)
    )
    log.debug(s"ChargingPlugInEvent: $chargingPlugInEvent")
    getBeamServices.matsimServices.getEvents.processEvent(chargingPlugInEvent)
  }

  /**
    * Calculates the duration of the refuel session, the provided energy and throws corresponding events
    * @param currentTick current time
    * @param chargingVehicle vehicle charging information
    */
  def processEndChargingEvents(currentTick: Int, chargingVehicle: ChargingVehicle): Unit = {
    val (totDuration, totEnergy) = chargingVehicle.calculateChargingSessionLengthAndEnergyInJoule
    val vehicle = chargingVehicle.vehicle
    val stall = chargingVehicle.stall
    log.debug(
      s"Vehicle ${chargingVehicle.vehicle} was disconnected at time {} with {} J delivered during {} sec",
      currentTick,
      totEnergy,
      totDuration
    )
    // Refuel Session
    val refuelSessionEvent = new RefuelSessionEvent(
      currentTick,
      stall.copy(locationUTM = getBeamServices.geo.utm2Wgs(stall.locationUTM)),
      totEnergy,
      vehicle.primaryFuelLevelInJoules - totEnergy,
      totDuration,
      vehicle.id,
      vehicle.beamVehicleType,
      chargingVehicle.personId,
      chargingVehicle.activityType,
      chargingVehicle.shiftStatus
    )
    log.debug(s"RefuelSessionEvent: $refuelSessionEvent")
    getBeamServices.matsimServices.getEvents.processEvent(refuelSessionEvent)
    val chargingPlugOutEvent: ChargingPlugOutEvent = new ChargingPlugOutEvent(
      currentTick,
      stall.copy(locationUTM = getBeamServices.geo.utm2Wgs(stall.locationUTM)),
      vehicle.id,
      vehicle.primaryFuelLevelInJoules,
      Some(vehicle.secondaryFuelLevelInJoules)
    )
    log.debug(s"ChargingPlugOutEvent: $chargingPlugOutEvent")
    getBeamServices.matsimServices.getEvents.processEvent(chargingPlugOutEvent)
  }
}
