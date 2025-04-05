package beam.agentsim.infrastructure

import beam.agentsim.events.{ChargingPlugInEvent, ChargingPlugOutEvent, RefuelSessionEvent}
import beam.agentsim.infrastructure.ChargingNetwork.ChargingStatus.Connected
import beam.agentsim.infrastructure.ChargingNetwork.{ChargingCycle, ChargingVehicle}
import beam.agentsim.infrastructure.ChargingNetworkManager.ChargingTimeOutTrigger
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.sim.config.BeamConfig.Beam.Agentsim
import beam.utils.DateUtils

trait ChargingNetworkManagerHelper extends {
  this: ChargingNetworkManager =>

  protected lazy val endOfSimulationTime: Int = DateUtils.getEndOfTime(beamConfig)
  protected lazy val cnmConfig: Agentsim.ChargingNetworkManager = beamConfig.beam.agentsim.chargingNetworkManager
  private lazy val parallelismWindow: Int = beamConfig.beam.agentsim.schedulerParallelismWindow

  /**
    * if this is the last timebin of the simulation
    *
    * @param tick current tick
    * @return a boolean
    */
  protected def isEndOfSimulation(tick: Int): Boolean = nextTimeBin(tick) >= endOfSimulationTime

  /**
    * if charging won't complete during the current cycle
    *
    * @param cycle the latest charging cycle
    * @return
    */
  private def chargingNotCompleteUsing(cycle: ChargingCycle): Boolean =
    (cycle.endTime - cycle.startTime) >= cycle.maxDuration

  /**
    * Get current time bin
    *
    * @param tick time
    * @return
    */
  protected def currentTimeBin(tick: Int): Int = cnmConfig.timeStepInSeconds * (tick / cnmConfig.timeStepInSeconds)

  /**
    * get next time bin
    *
    * @param tick time
    * @return
    */
  protected def nextTimeBin(tick: Int): Int = currentTimeBin(tick) + cnmConfig.timeStepInSeconds

  /**
    * @param chargingVehicle the vehicle being charged
    * @param startTime the start time
    * @param endTime the end time
    * @param interruptCharging True if the charging should be interrupted
    * @return
    */
  protected def dispatchEnergyAndProcessChargingCycle(
    chargingVehicle: ChargingVehicle,
    startTime: Int,
    endTime: Int,
    interruptCharging: Boolean = false
  ): Option[ScheduleTrigger] = {
    assume(endTime - startTime >= 0, s"timeInterval should not be negative! startTime $startTime endTime $endTime")
    // Calculate the energy to charge each vehicle connected to the a charging station
    val endOfMaxSessionTime = chargingEndTimeInSeconds.getOrElse(chargingVehicle.personId, endTime)
    val shouldEndAtMaybe = chargingVehicle.chargingShouldEndAt.map(_ - parallelismWindow)
    val updatedEndTime = Math.min(endOfMaxSessionTime, shouldEndAtMaybe.getOrElse(endTime))
    chargingVehicle.checkAndCorrectCycleAfterInterruption(updatedEndTime)
    val maxCycleDuration = shouldEndAtMaybe match {
      case Some(shouldEndAt) => Math.min(nextTimeBin(startTime) - startTime, shouldEndAt - startTime)
      case None              => nextTimeBin(startTime) - startTime
    }
    val newCycle = sitePowerManager.dispatchEnergy(startTime, updatedEndTime, maxCycleDuration, chargingVehicle)
    log.debug(
      s"dispatchEnergyAndProcessChargingCycle. " +
      s"startTime:$startTime, endTime:$endTime, updatedEndTime:$updatedEndTime," +
      s"cycle:$newCycle, chargingVehicle:$chargingVehicle"
    )
    chargingVehicle.processCycle(newCycle).flatMap {
      case cycle if interruptCharging || (chargingNotCompleteUsing(cycle) && !isEndOfSimulation(startTime)) =>
        // Charging is being interrupted. We will not return a ChargingTimeOutTrigger
        log.debug(
          s"Vehicle {} is still charging @ Stall: {}. Provided energy: {} J. State of Charge: {}",
          chargingVehicle.vehicle.id,
          chargingVehicle.stall,
          cycle.energyToCharge,
          chargingVehicle.vehicle.primaryFuelLevelInJoules / chargingVehicle.vehicle.beamVehicleType.primaryFuelCapacityInJoule
        )
        None
      case cycle =>
        // charging is going to end during this current session
        Some(ScheduleTrigger(ChargingTimeOutTrigger(cycle.endTime, chargingVehicle), self))
    }
  }

  /**
    * Connect the vehicle
    *
    * @param tick            current time
    * @param chargingVehicle charging vehicle information
    */
  protected def handleStartCharging(tick: Int, chargingVehicle: ChargingVehicle): Unit = {
    val nextTick = nextTimeBin(tick)
    val vehicle = chargingVehicle.vehicle
    if (vehicle.stall.isEmpty)
      vehicle.useParkingStall(chargingVehicle.stall)
    log.debug(s"Starting charging for vehicle $vehicle at $tick")
    processStartChargingEvent(tick, chargingVehicle)
    // we need to interrupt charging here in order to charge vehicle from tick to timeBin(tick)
    // from timeBin(tick) to the next time bin it will be processed while processing PlanEnergyDispatchTrigger
    dispatchEnergyAndProcessChargingCycle(chargingVehicle, tick, nextTick, interruptCharging = true)
  }

  /**
    * Disconnect the vehicle
    *
    * @param tick            current time
    * @param chargingVehicle charging vehicle information
    * @return true if EndingRefuelSession is sent to the agent
    */
  protected def handleEndCharging(tick: Int, chargingVehicle: ChargingVehicle): Option[ChargingVehicle] = {
    val result = chargingVehicle.chargingStatus.last.status match {
      case Connected =>
        chargingVehicle.chargingStation.endCharging(chargingVehicle.vehicle.id, tick) orElse {
          log.debug(s"Vehicle ${chargingVehicle.vehicle.id} has already ended charging")
          None
        }
      case _ => Some(chargingVehicle)
    }
    if (result.isDefined) {
      handleRefueling(chargingVehicle)
      processEndChargingEvents(tick, chargingVehicle)
    }
    result
  }

  /**
    * Refuel the vehicle using last charging session and collect the corresponding load
    *
    * @param chargingVehicle vehicle charging information
    */
  protected def handleRefueling(chargingVehicle: ChargingVehicle): Unit = {
    chargingVehicle.refuel.foreach {
      case ChargingCycle(startTime, endTime, _, _, energyToChargeIfUnconstrained, _, _) =>
        val station = chargingVehicle.chargingStation
        sitePowerManager.collectObservedLoadInKW(startTime, endTime - startTime, energyToChargeIfUnconstrained, station)
    }
  }

  /**
    * process the event ChargingPlugInEvent
    *
    * @param currentTick     current time
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
    *
    * @param currentTick     current time
    * @param chargingVehicle vehicle charging information
    */
  def processEndChargingEvents(currentTick: Int, chargingVehicle: ChargingVehicle): Unit = {
    val (totDuration, _) = chargingVehicle.calculateChargingSessionLengthAndEnergyInJoule
    val vehicle = chargingVehicle.vehicle
    val stall = chargingVehicle.stall
    val addedFuelLevel = vehicle.primaryFuelLevelInJoules - chargingVehicle.arrivalFuelLevel
    log.debug(
      s"Vehicle ${chargingVehicle.vehicle} was disconnected at time {} with {} J delivered during {} sec",
      currentTick,
      addedFuelLevel,
      totDuration
    )
    // Refuel Session
    val refuelSessionEvent = new RefuelSessionEvent(
      currentTick,
      stall.copy(locationUTM = getBeamServices.geo.utm2Wgs(stall.locationUTM)),
      addedFuelLevel,
      chargingVehicle.arrivalFuelLevel,
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
