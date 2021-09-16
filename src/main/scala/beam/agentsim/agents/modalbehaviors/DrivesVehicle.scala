package beam.agentsim.agents.modalbehaviors

import akka.actor.FSM.Failure
import akka.actor.{ActorRef, Stash}
import beam.agentsim.Resource.{NotifyVehicleIdle, ReleaseParkingStall}
import beam.agentsim.agents.BeamAgent
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.modalbehaviors.DrivesVehicle._
import beam.agentsim.agents.parking.ChoosesParking.ConnectingToChargingPoint
import beam.agentsim.agents.ridehail.RideHailAgent._
import beam.agentsim.agents.vehicles.AccessErrorCodes.VehicleFullError
import beam.agentsim.agents.vehicles.BeamVehicle.{BeamVehicleState, FuelConsumed}
import beam.agentsim.agents.vehicles.VehicleProtocol._
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.RefuelSessionEvent.NotApplicable
import beam.agentsim.events._
import beam.agentsim.infrastructure.ChargingNetworkManager._
import beam.agentsim.infrastructure.ParkingStall
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.agentsim.scheduler.{HasTriggerId, Trigger}
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.WALK
import beam.router.model.{BeamLeg, BeamPath}
import beam.router.osm.TollCalculator
import beam.router.skim.event.TransitCrowdingSkimmerEvent
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.sim.{BeamConfigChangesObservable, BeamScenario, BeamServices}
import beam.utils.NetworkHelper
import beam.utils.logging.ExponentialLazyLogging
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.{
  LinkEnterEvent,
  LinkLeaveEvent,
  VehicleEntersTrafficEvent,
  VehicleLeavesTrafficEvent
}
import org.matsim.api.core.v01.population.Person
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.vehicles.Vehicle

import scala.collection.mutable
import scala.language.postfixOps

/**
  * DrivesVehicle
  */
object DrivesVehicle {

  def resolvePassengerScheduleConflicts(
    stopTick: Int,
    oldPassengerSchedule: PassengerSchedule,
    updatedPassengerSchedule: PassengerSchedule,
    networkHelper: NetworkHelper,
    geoUtils: GeoUtils
  ): PassengerSchedule = {
    // First attempt to find the link in updated that corresponds to the stopping link in old
    val stoppingLink = oldPassengerSchedule.linkAtTime(stopTick)
    val updatedLegsInSchedule = updatedPassengerSchedule.schedule.keys.toList
    val startingLeg = updatedLegsInSchedule.reverse.find(_.travelPath.linkIds.contains(stoppingLink)) match {
      case Some(leg) =>
        leg
      case None =>
        // Instead we will have to find the starting point using closest Euclidean distance of the links
        val stoppingCoord = networkHelper.getLink(stoppingLink).get.getCoord
        val allLinks = updatedLegsInSchedule.flatMap(_.travelPath.linkIds)
        val startingLink = allLinks(
          allLinks
            .map(networkHelper.getLink(_).get.getCoord)
            .map(geoUtils.distUTMInMeters(_, stoppingCoord))
            .zipWithIndex
            .min
            ._2
        )
        updatedLegsInSchedule.reverse.find(_.travelPath.linkIds.contains(startingLink)).get
    }
    val indexOfStartingLink = startingLeg.travelPath.linkIds.indexWhere(_ == stoppingLink)
    val newLinks = startingLeg.travelPath.linkIds.drop(indexOfStartingLink)
    val newDistance = newLinks.map(networkHelper.getLink(_).map(_.getLength.toInt).getOrElse(0)).sum
    val newStart = SpaceTime(geoUtils.utm2Wgs(networkHelper.getLink(newLinks.head).get.getCoord), stopTick)
    val newDuration = if (newLinks.size <= 1) { 0 }
    else {
      math.round(startingLeg.travelPath.linkTravelTime.drop(indexOfStartingLink).tail.sum.toFloat)
    }
    val newTravelPath = BeamPath(
      newLinks,
      startingLeg.travelPath.linkTravelTime.drop(indexOfStartingLink),
      None,
      newStart,
      startingLeg.travelPath.endPoint.copy(time = newStart.time + newDuration),
      newDistance
    )
    val updatedStartingLeg = BeamLeg(stopTick, startingLeg.mode, newTravelPath.duration, newTravelPath)
    val indexOfStartingLeg = updatedLegsInSchedule.indexOf(startingLeg)
    val newLegsInSchedule = BeamLeg.makeVectorLegsConsistentAsTrip(
      updatedLegsInSchedule.slice(0, indexOfStartingLeg) ++ (updatedStartingLeg +: updatedLegsInSchedule
        .slice(indexOfStartingLeg + 1, updatedPassengerSchedule.schedule.size))
    )
    var newPassSchedule = PassengerSchedule().addLegs(newLegsInSchedule)
    updatedPassengerSchedule.uniquePassengers.foreach { pass =>
      val indicesOfMatchingElements =
        updatedPassengerSchedule.legsWithPassenger(pass).toIndexedSeq.map(updatedLegsInSchedule.indexOf(_))
      newPassSchedule = newPassSchedule.addPassenger(pass, indicesOfMatchingElements.map(newLegsInSchedule(_)))
    }
    updatedPassengerSchedule.passengersWhoNeverBoard.foreach { pass =>
      newPassSchedule = newPassSchedule.removePassengerBoarding(pass)
    }
    newPassSchedule
  }

  def stripLiterallyDrivingData(data: DrivingData): DrivingData = {
    data match {
      case LiterallyDrivingData(subData, _, _) =>
        subData
      case _ =>
        data
    }
  }

  sealed trait VehicleOrToken {
    def id: Id[BeamVehicle]
    def vehicle: BeamVehicle
    def streetVehicle: StreetVehicle
  }

  case class EndRefuelData(chargingEndTick: Int, energyDelivered: Double)

  case class ActualVehicle(vehicle: BeamVehicle) extends VehicleOrToken {
    override def id: Id[BeamVehicle] = vehicle.id

    override def streetVehicle: StreetVehicle = vehicle.toStreetVehicle
  }

  case class Token(override val id: Id[BeamVehicle], manager: ActorRef, vehicle: BeamVehicle) extends VehicleOrToken {
    override def streetVehicle: StreetVehicle = vehicle.toStreetVehicle
  }

  case class StartLegTrigger(tick: Int, beamLeg: BeamLeg) extends Trigger

  case class EndLegTrigger(tick: Int) extends Trigger

  case class AlightVehicleTrigger(
    tick: Int,
    vehicleId: Id[BeamVehicle],
    fuelConsumed: Option[FuelConsumed] = None
  ) extends Trigger

  case class BoardVehicleTrigger(tick: Int, vehicleId: Id[BeamVehicle]) extends Trigger

  case class StopDriving(tick: Int, triggerId: Long) extends HasTriggerId

  case class BeamVehicleStateUpdate(id: Id[BeamVehicle], vehicleState: BeamVehicleState)

  def processLinkEvents(eventsManager: EventsManager, beamVehicleId: Id[BeamVehicle], leg: BeamLeg): Unit = {
    val path = leg.travelPath
    if (path.linkTravelTime.nonEmpty & path.linkIds.size > 1) {
      val vehicleId = Id.create(beamVehicleId.toString, classOf[Vehicle])
      val links = path.linkIds
      val linkTravelTime = path.linkTravelTime
      var i: Int = 0
      var curTime = leg.startTime
      // `links.length - 1` because we don't need the travel time for the last link
      while (i < links.length - 1) {
        val from = links(i)
        val to = links(i + 1)
        val timeAtNode = math.round(linkTravelTime(i).toFloat)
        curTime = curTime + timeAtNode
        eventsManager.processEvent(new LinkLeaveEvent(curTime, vehicleId, Id.createLinkId(from)))
        eventsManager.processEvent(new LinkEnterEvent(curTime, vehicleId, Id.createLinkId(to)))
        i += 1
      }
    }
  }
}

trait DrivesVehicle[T <: DrivingData] extends BeamAgent[T] with Stash with ExponentialLazyLogging {
  protected val transportNetwork: TransportNetwork
  protected val parkingManager: ActorRef
  protected val chargingNetworkManager: ActorRef
  protected val tollCalculator: TollCalculator
  protected val beamScenario: BeamScenario
  protected val beamServices: BeamServices
  protected val networkHelper: NetworkHelper
  protected val geo: GeoUtils
  private var tollsAccumulated = 0.0
  protected val beamVehicles: mutable.Map[Id[BeamVehicle], VehicleOrToken] = mutable.Map()
  protected val potentiallyChargingBeamVehicles: mutable.Map[Id[BeamVehicle], VehicleOrToken] = mutable.Map()

  protected def currentBeamVehicle: BeamVehicle =
    beamVehicles(stateData.currentVehicle.head).asInstanceOf[ActualVehicle].vehicle

  protected val fuelConsumedByTrip: mutable.Map[Id[Person], FuelConsumed] = mutable.Map()
  var latestObservedTick: Int = 0

  private def beamConfig: BeamConfig = BeamConfigChangesObservable.lastBeamConfig

  case class PassengerScheduleEmptyMessage(
    lastVisited: SpaceTime,
    toll: Double,
    triggerId: Long,
    fuelConsumed: Option[FuelConsumed] = None
  ) extends HasTriggerId

  case class LastLegPassengerSchedule(triggerId: Long) extends HasTriggerId

  var nextNotifyVehicleResourceIdle: Option[NotifyVehicleIdle] = None

  def updateFuelConsumedByTrip(idp: Id[Person], fuelConsumed: FuelConsumed, factor: Int = 1): Unit = {
    val existingFuel = fuelConsumedByTrip.getOrElse(idp, FuelConsumed(0, 0))
    fuelConsumedByTrip(idp) = FuelConsumed(
      existingFuel.primaryFuel + fuelConsumed.primaryFuel / factor,
      existingFuel.secondaryFuel + fuelConsumed.secondaryFuel / factor
    )
  }

  def updateLatestObservedTick(newTick: Int): Unit = if (newTick > latestObservedTick) latestObservedTick = newTick

  when(Driving) {
    case _ @Event(
          TriggerWithId(EndLegTrigger(tick), triggerId),
          LiterallyDrivingData(data, legEndingAt, _)
        ) if tick == legEndingAt =>
      updateLatestObservedTick(tick)
      log.debug("state(DrivesVehicle.Driving): EndLegTrigger({}) for driver {}", tick, id)
      val currentLeg = data.passengerSchedule.schedule.keys.view
        .drop(data.currentLegPassengerScheduleIndex)
        .headOption
        .getOrElse(throw new RuntimeException("Current Leg is not available."))
      val currentVehicleUnderControl = data.currentVehicle.headOption
        .getOrElse(throw new RuntimeException("Current Vehicle is not available."))
      val isLastLeg = data.currentLegPassengerScheduleIndex + 1 == data.passengerSchedule.schedule.size
      val fuelConsumed =
        currentBeamVehicle.useFuel(
          currentLeg,
          beamScenario,
          networkHelper,
          eventsManager,
          eventBuilderActor,
          beamServices.beamCustomizationAPI.beamVehicleAfterUseFuelHook
        )

      currentBeamVehicle.spaceTime = geo.wgs2Utm(currentLeg.travelPath.endPoint)

      var nbPassengers = data.passengerSchedule.schedule(currentLeg).riders.size
      if (nbPassengers > 0) {
        if (currentLeg.mode.isTransit) {
          val transitCapacity = beamConfig.beam.agentsim.tuning.transitCapacity
          nbPassengers = (nbPassengers / transitCapacity.getOrElse(1.0)).toInt
        }
        data.passengerSchedule.schedule(currentLeg).riders foreach { rider =>
          updateFuelConsumedByTrip(rider.personId, fuelConsumed, nbPassengers)
        }
      } else {
        updateFuelConsumedByTrip(id.asInstanceOf[Id[Person]], fuelConsumed)
      }

      if (isLastLeg) {
        nextNotifyVehicleResourceIdle = Some(
          NotifyVehicleIdle(
            currentVehicleUnderControl,
            geo.wgs2Utm(currentLeg.travelPath.endPoint.copy(time = tick)),
            data.passengerSchedule,
            currentBeamVehicle.getState,
            data.geofence,
            triggerId
          )
        )
      }

      data.passengerSchedule.schedule(currentLeg).alighters.foreach { pv =>
        logDebug(
          s"Scheduling AlightVehicleTrigger for Person ${pv.personId} from vehicle ${data.currentVehicle.head} @ $tick"
        )
        scheduler ! ScheduleTrigger(
          AlightVehicleTrigger(
            tick,
            data.currentVehicle.head,
            Some(fuelConsumedByTrip(pv.personId))
          ),
          pv.personRef
        )
        fuelConsumedByTrip.remove(pv.personId)
      }

      // EventsToLegs fails for our way of reporting e.g. walk/car/walk trips,
      // or any trips with multiple link-based vehicles where there isn't an
      // activity in between.
      // We help ourselves by not emitting link events for walking, but a better criterion
      // would be to only emit link events for the "main" leg.
      if (currentLeg.mode != WALK) {
        processLinkEvents(eventsManager, data.currentVehicle.head, currentLeg)
      }

      logDebug(s"PathTraversal @ $tick")
      eventsManager.processEvent(
        new VehicleLeavesTrafficEvent(
          tick,
          id.asInstanceOf[Id[Person]],
          Id.createLinkId(currentLeg.travelPath.linkIds.lastOption.getOrElse(Int.MinValue).toString),
          data.currentVehicle.head,
          "car",
          0.0
        )
      )

      val tollOnCurrentLeg = toll(currentLeg)
      tollsAccumulated += tollOnCurrentLeg
      val riders = data.passengerSchedule.schedule(currentLeg).riders.toIndexedSeq.map(_.personId)
      val pte = PathTraversalEvent(
        tick,
        currentVehicleUnderControl,
        id.toString,
        currentBeamVehicle.beamVehicleType,
        riders.size,
        currentLeg,
        fuelConsumed.primaryFuel,
        fuelConsumed.secondaryFuel,
        currentBeamVehicle.primaryFuelLevelInJoules,
        currentBeamVehicle.secondaryFuelLevelInJoules,
        tollOnCurrentLeg,
        /*
          fuelConsumed.fuelConsumptionData.map(x=>(x.linkId, x.linkNumberOfLanes)),
          fuelConsumed.fuelConsumptionData.map(x=>(x.linkId, x.freeFlowSpeed)),
          fuelConsumed.primaryLoggingData.map(x=>(x.linkId, x.gradientOption)),
          fuelConsumed.fuelConsumptionData.map(x=>(x.linkId, x.linkLength)),
          fuelConsumed.primaryLoggingData.map(x=>(x.linkId, x.rate)),
          fuelConsumed.primaryLoggingData.map(x=>(x.linkId, x.consumption)),
          fuelConsumed.secondaryLoggingData.map(x=>(x.linkId, x.rate)),
          fuelConsumed.secondaryLoggingData.map(x=>(x.linkId, x.consumption))*/
        riders
      )

      eventsManager.processEvent(pte)
      generateTCSEventIfPossible(pte)

      if (!isLastLeg) {
        if (data.hasParkingBehaviors) {
          holdTickAndTriggerId(tick, triggerId)
          log.debug(s"state(DrivesVehicle.Driving) $id is going to ReadyToChooseParking")
          goto(ReadyToChooseParking) using data
            .withCurrentLegPassengerScheduleIndex(data.currentLegPassengerScheduleIndex + 1)
            .asInstanceOf[T]
        } else {
          val nextLeg =
            data.passengerSchedule.schedule.keys.view
              .drop(data.currentLegPassengerScheduleIndex + 1)
              .head
          val startLegTriggerTick = if (nextLeg.startTime < tick) {
            logger.warn(s"Start time of next leg ${nextLeg.startTime} was less than current tick $tick.")
            tick
          } else {
            nextLeg.startTime
          }
          log.debug(s"state(DrivesVehicle.Driving) $id is going to WaitingToDrive")
          goto(WaitingToDrive) using stripLiterallyDrivingData(data)
            .withCurrentLegPassengerScheduleIndex(data.currentLegPassengerScheduleIndex + 1)
            .asInstanceOf[T] replying CompletionNotice(
            triggerId,
            Vector(ScheduleTrigger(StartLegTrigger(startLegTriggerTick, nextLeg), self))
          )
        }
      } else {
        var waitForConnectionToChargingPoint = false
        if (data.hasParkingBehaviors) {
          currentBeamVehicle.reservedStall.foreach { stall: ParkingStall =>
            currentBeamVehicle.useParkingStall(stall)
            val parkEvent = ParkingEvent(
              time = tick,
              stall = stall,
              locationWGS = geo.utm2Wgs(stall.locationUTM),
              vehicleId = currentBeamVehicle.id,
              driverId = id.toString
            )
            eventsManager.processEvent(parkEvent) // nextLeg.endTime -> to fix repeated path traversal

            // charge vehicle
            if (currentBeamVehicle.isBEV | currentBeamVehicle.isPHEV) {
              stall.chargingPointType match {
                case Some(_) =>
                  log.debug("Sending ChargingPlugRequest to chargingNetworkManager at {}", tick)
                  chargingNetworkManager ! ChargingPlugRequest(
                    tick,
                    currentBeamVehicle,
                    stall,
                    Id.createPersonId(id),
                    triggerId,
                    shiftStatus = NotApplicable
                  )
                  waitForConnectionToChargingPoint = true
                case None => // this should only happen rarely
                  log.debug(
                    "Charging request by vehicle {} ({}) on a spot without a charging point (parkingZoneId: {}). This is not handled yet!",
                    currentBeamVehicle.id,
                    if (currentBeamVehicle.isBEV) "BEV" else if (currentBeamVehicle.isPHEV) "PHEV" else "non-electric",
                    stall.parkingZoneId
                  )
              }
            }
          }
          currentBeamVehicle.setReservedParkingStall(None)
        }
        holdTickAndTriggerId(tick, triggerId)
        if (waitForConnectionToChargingPoint) {
          log.debug(s"state(DrivesVehicle.Driving) $id is going to ConnectingToChargingPoint")
          goto(ConnectingToChargingPoint) using data.asInstanceOf[T]
        } else {
          self ! LastLegPassengerSchedule(triggerId)
          log.debug(s"state(DrivesVehicle.Driving) $id is going to DrivingInterrupted with $triggerId")
          goto(DrivingInterrupted) using data.asInstanceOf[T]
        }
      }

    //TODO Need explanation as to why we do nothing if we receive EndLeg but data is not type LiterallyDrivingData
    case ev @ Event(TriggerWithId(EndLegTrigger(tick), triggerId), data) =>
      updateLatestObservedTick(tick)
      log.debug("state(DrivesVehicle.Driving): {}", ev)

      log.debug(
        "DrivesVehicle.IgnoreEndLegTrigger: vehicleId({}), tick({}), triggerId({}), data({})",
        id,
        tick,
        triggerId,
        data
      )
      stay replying CompletionNotice(triggerId, Vector())

    case ev @ Event(Interrupt(interruptId, _, triggerId), data) =>
      log.debug("state(DrivesVehicle.Driving): {}", ev)
      goto(DrivingInterrupted) replying InterruptedWhileDriving(
        interruptId,
        currentBeamVehicle.id,
        latestObservedTick,
        data.passengerSchedule,
        data.currentLegPassengerScheduleIndex,
        triggerId
      )

    case ev @ Event(StartingRefuelSession(_, _, _), _) =>
      log.debug("state(DrivesVehicle.Driving.StartingRefuelSession): {}", ev)
      stay()
    case ev @ Event(UnhandledVehicle(_, _, _), _) =>
      log.error("state(DrivesVehicle.Driving.UnhandledVehicle): {}", ev)
      stay()
    case ev @ Event(WaitingToCharge(_, _, _, _), _) =>
      log.error("state(DrivesVehicle.Driving.WaitingInLine): {}. This probably should not happen", ev)
      stay()

  }

  when(DrivingInterrupted) {
    case ev @ Event(StopDriving(stopTick, triggerId), LiterallyDrivingData(data, _, _)) =>
      log.debug("state(DrivesVehicle.DrivingInterrupted): {}", ev)
      val currentLeg = data.passengerSchedule.schedule.keys.view
        .drop(data.currentLegPassengerScheduleIndex)
        .headOption
        .getOrElse(throw new RuntimeException("Current Leg is not available."))
      val currentVehicleUnderControl = data.currentVehicle.headOption
        .getOrElse(throw new RuntimeException("Current Vehicle is not available."))

      val updatedStopTick = math.max(stopTick, currentLeg.startTime)
      val partiallyCompletedBeamLeg = currentLeg.subLegThrough(updatedStopTick, networkHelper, geo)
      val riders = data.passengerSchedule.schedule(currentLeg).riders.toIndexedSeq.map(_.personId)

      val currentLocation = if (updatedStopTick > currentLeg.startTime) {
        val fuelConsumed =
          currentBeamVehicle.useFuel(
            partiallyCompletedBeamLeg,
            beamScenario,
            networkHelper,
            eventsManager,
            eventBuilderActor,
            beamServices.beamCustomizationAPI.beamVehicleAfterUseFuelHook
          )

        val tollOnCurrentLeg = toll(partiallyCompletedBeamLeg)
        tollsAccumulated += tollOnCurrentLeg
        val pte = PathTraversalEvent(
          updatedStopTick,
          currentVehicleUnderControl,
          id.toString,
          currentBeamVehicle.beamVehicleType,
          riders.size,
          partiallyCompletedBeamLeg,
          fuelConsumed.primaryFuel,
          fuelConsumed.secondaryFuel,
          currentBeamVehicle.primaryFuelLevelInJoules,
          currentBeamVehicle.secondaryFuelLevelInJoules,
          tollOnCurrentLeg,
          riders
        )
        eventsManager.processEvent(pte)
        generateTCSEventIfPossible(pte)
        partiallyCompletedBeamLeg.travelPath.endPoint
      } else {
        currentLeg.travelPath.startPoint
      }

      nextNotifyVehicleResourceIdle = Some(
        NotifyVehicleIdle(
          currentVehicleUnderControl,
          geo.wgs2Utm(currentLocation.copy(time = updatedStopTick)),
          data.passengerSchedule,
          currentBeamVehicle.getState,
          data.geofence,
          triggerId
        )
      )

      eventsManager.processEvent(
        new VehicleLeavesTrafficEvent(
          stopTick,
          id.asInstanceOf[Id[Person]],
          null,
          data.currentVehicle.head,
          "car",
          0.0
        )
      )

      if (riders.isEmpty) {
        self ! PassengerScheduleEmptyMessage(
          currentLocation,
          tollsAccumulated,
          triggerId = triggerId
        )
        tollsAccumulated = 0.0
        goto(PassengerScheduleEmptyInterrupted) using data
          .withCurrentLegPassengerScheduleIndex(data.currentLegPassengerScheduleIndex + 1)
          .asInstanceOf[T]
      } else {
        // In this case our passenger schedule isn't empty so we go directly to idle interrupted
        goto(IdleInterrupted) using stripLiterallyDrivingData(data).asInstanceOf[T]
      }
    case ev @ Event(Resume(_), _) =>
      log.debug("state(DrivesVehicle.DrivingInterrupted): {}", ev)
      goto(Driving)
    case ev @ Event(TriggerWithId(EndLegTrigger(_), _), _) =>
      log.debug("state(DrivesVehicle.DrivingInterrupted): {}", ev)
      stash()
      stay
    case ev @ Event(Interrupt(_, _, _), _) =>
      log.debug("state(DrivesVehicle.DrivingInterrupted): {}", ev)
      stash()
      stay
    case ev @ Event(StartingRefuelSession(_, _, _), _) =>
      log.debug("state(DrivesVehicle.DrivingInterrupted): {}", ev)
      stay
    case _ @Event(LastLegPassengerSchedule(triggerId), data) =>
      log.debug(s"state(DrivesVehicle.DrivingInterrupted): LastLegPassengerSchedule with $triggerId for $id")
      self ! PassengerScheduleEmptyMessage(
        geo.wgs2Utm(
          data.passengerSchedule.schedule
            .drop(data.currentLegPassengerScheduleIndex)
            .head
            ._1
            .travelPath
            .endPoint
        ),
        tollsAccumulated,
        triggerId,
        Some(fuelConsumedByTrip.getOrElse(id.asInstanceOf[Id[Person]], FuelConsumed(0, 0)))
      )
      fuelConsumedByTrip.remove(id.asInstanceOf[Id[Person]])
      tollsAccumulated = 0.0
      goto(PassengerScheduleEmpty) using stripLiterallyDrivingData(data)
        .withCurrentLegPassengerScheduleIndex(data.currentLegPassengerScheduleIndex + 1)
        .asInstanceOf[T]

  }

  private def generateTCSEventIfPossible(pte: PathTraversalEvent): Unit = {
    (pte.fromStopIndex, pte.toStopIndex) match {
      case (Some(fromStopIdx), Some(_)) =>
        eventsManager.processEvent(
          new TransitCrowdingSkimmerEvent(
            pte.time,
            beamConfig.beam.router.skim.transit_crowding_skimmer,
            pte.vehicleId,
            fromStopIdx,
            pte.numberOfPassengers,
            pte.capacity
          )
        )
      case _ =>
    }
  }

  when(WaitingToDrive) {
    case _ @Event(TriggerWithId(StartLegTrigger(tick, newLeg), triggerId), data)
        if data.legStartsAt.isEmpty || tick == data.legStartsAt.get =>
      updateLatestObservedTick(tick)
      log.debug("state(DrivesVehicle.WaitingToDrive): StartLegTrigger({},{}) for driver {}", tick, newLeg, id)

      if (data.currentVehicle.isEmpty) {
        stop(Failure("person received StartLegTrigger for leg {} but has an empty data.currentVehicle", newLeg))
      } else {
        // Un-Park if necessary, this should only happen with RideHailAgents
        data.currentVehicle.headOption match {
          case Some(currentVehicleUnderControl) =>
            assert(
              currentBeamVehicle.id == currentVehicleUnderControl,
              currentBeamVehicle.id + " " + currentVehicleUnderControl
            )
            currentBeamVehicle.stall match {
              case Some(theStall) if !currentBeamVehicle.isCAV =>
                parkingManager ! ReleaseParkingStall(theStall, triggerId)
                currentBeamVehicle.unsetParkingStall()
              case _ =>
            }
          case None =>
        }
        val triggerToSchedule: Vector[ScheduleTrigger] = data.passengerSchedule
          .schedule(newLeg)
          .boarders
          .map { personVehicle =>
            logDebug(
              s"Scheduling BoardVehicleTrigger at $tick for Person ${personVehicle.personId} into vehicle ${data.currentVehicle.head} @ $tick"
            )
            ScheduleTrigger(
              BoardVehicleTrigger(tick, data.currentVehicle.head),
              personVehicle.personRef
            )
          }
          .toVector
        eventsManager.processEvent(
          new VehicleEntersTrafficEvent(
            tick,
            Id.createPersonId(id),
            Id.createLinkId(newLeg.travelPath.linkIds.headOption.getOrElse(Int.MinValue).toString),
            data.currentVehicle.head,
            "car",
            1.0
          )
        )
        // Produce link events for this trip (the same ones as in PathTraversalEvent).
        val beamLeg = data.passengerSchedule.schedule
          .drop(data.currentLegPassengerScheduleIndex)
          .head
          ._1
        val endTime = tick + beamLeg.duration
        goto(Driving) using LiterallyDrivingData(data, endTime, Some(tick))
          .asInstanceOf[T] replying CompletionNotice(
          triggerId,
          triggerToSchedule ++ Vector(ScheduleTrigger(EndLegTrigger(endTime), self))
        )
      }
    case ev @ Event(Interrupt(interruptId, _, triggerId), _) =>
      log.debug("state(DrivesVehicle.WaitingToDrive): {}", ev)
      goto(WaitingToDriveInterrupted) replying InterruptedWhileWaitingToDrive(
        interruptId,
        currentBeamVehicle.id,
        latestObservedTick,
        triggerId
      )

    case ev @ Event(NotifyVehicleResourceIdleReply(triggerId: Long, newTriggers: Seq[ScheduleTrigger], _), _) =>
      log.debug("state(DrivesVehicle.WaitingToDrive.NotifyVehicleResourceIdleReply): {}", ev)

      if (!_currentTriggerId.contains(triggerId)) {
        log.error(
          "Driver {}: local triggerId {} does not match the id received from resource manager {}",
          id,
          _currentTriggerId,
          triggerId
        )
      }

      _currentTriggerId match {
        case Some(_) =>
          val (_, triggerId) = releaseTickAndTriggerId()
          scheduler ! CompletionNotice(triggerId, newTriggers)
        case None =>
      }

      stay()

  }

  when(WaitingToDriveInterrupted) {
    case ev @ Event(Resume(_), _) =>
      log.debug("state(DrivesVehicle.WaitingToDriveInterrupted): {}", ev)
      goto(WaitingToDrive)

    case ev @ Event(TriggerWithId(StartLegTrigger(_, _), _), _) =>
      log.debug("state(DrivesVehicle.WaitingToDriveInterrupted): {}", ev)
      stash()
      stay
    case _ @Event(NotifyVehicleResourceIdleReply(_, _, _), _) =>
      stash()
      stay

  }

  val drivingBehavior: StateFunction = {
    case ev @ Event(req: ReservationRequest, data)
        if !hasRoomFor(
          data.passengerSchedule,
          req,
          currentBeamVehicle
        ) =>
      log.debug("state(DrivesVehicle.drivingBehavior): {}", ev)
      stay() replying ReservationResponse(Left(VehicleFullError), req.triggerId)

    case ev @ Event(req: ReservationRequest, data) =>
      log.debug("state(DrivesVehicle.drivingBehavior): {}", ev)
      val legs = data.passengerSchedule.schedule
        .from(req.departFrom)
        .to(req.arriveAt)
        .keys
        .toSeq
      val legsInThePast = data.passengerSchedule.schedule
        .take(data.currentLegPassengerScheduleIndex)
        .from(req.departFrom)
        .to(req.arriveAt)
        .keys
        .toSeq
      if (legsInThePast.nonEmpty)
        log.debug("Legs in the past: {} -- {}", legsInThePast, req)
      val boardTrigger = if (legsInThePast.nonEmpty) {
        Vector(
          ScheduleTrigger(
            BoardVehicleTrigger(
              legsInThePast.head.startTime,
              data.currentVehicle.head
            ),
            sender()
          )
        )
      } else {
        Vector()
      }
      val alightTrigger = if (legsInThePast.nonEmpty && legsInThePast.size == legs.size) {
        Vector(
          ScheduleTrigger(
            AlightVehicleTrigger(
              legsInThePast.last.endTime,
              data.currentVehicle.head
            ),
            sender()
          )
        )
      } else {
        Vector()
      }

      val boardTrigger2 = data.passengerSchedule.schedule.keys.view
        .drop(data.currentLegPassengerScheduleIndex)
        .headOption match {
        case Some(currentLeg) =>
          if (stateName == Driving && legs.head == currentLeg) {
            Vector(
              ScheduleTrigger(
                BoardVehicleTrigger(
                  currentLeg.startTime,
                  data.currentVehicle.head
                ),
                sender()
              )
            )
          } else {
            Vector()
          }
        case None =>
          log.warning("Driver did not find a leg at currentLegPassengerScheduleIndex.")
          Vector()
      }
      stay() using data
        .withPassengerSchedule(
          data.passengerSchedule.addPassenger(req.passengerVehiclePersonId, legs)
        )
        .asInstanceOf[T] replying
      ReservationResponse(
        Right(
          ReserveConfirmInfo(boardTrigger ++ alightTrigger ++ boardTrigger2)
        ),
        req.triggerId
      )

    case ev @ Event(RemovePassengerFromTrip(id), data) =>
      log.debug("state(DrivesVehicle.drivingBehavior): {}", ev)
      stay() using data
        .withPassengerSchedule(
          PassengerSchedule(
            data.passengerSchedule.schedule ++ data.passengerSchedule.schedule
              .collect { case (leg, manifest) =>
                (
                  leg,
                  manifest.copy(
                    riders = manifest.riders - id,
                    alighters = manifest.alighters - id,
                    boarders = manifest.boarders - id
                  )
                )
              }
          )
        )
        .asInstanceOf[T]

    // The following 2 (Board and Alight) can happen idiosyncratically if a person ended up taking a much longer than expected
    // trip and meanwhile a CAV was scheduled to pick them up (and then drop them off) for the next trip, but they're still driving baby
    case Event(
          TriggerWithId(BoardVehicleTrigger(tick, vehicleId), triggerId),
          data @ LiterallyDrivingData(_, _, _)
        ) =>
      val currentLeg = data.passengerSchedule.schedule.keys.view
        .drop(data.currentLegPassengerScheduleIndex)
        .headOption
        .getOrElse(throw new RuntimeException("Current Leg is not available."))
      stay() replying CompletionNotice(
        triggerId,
        Vector(ScheduleTrigger(BoardVehicleTrigger(Math.max(currentLeg.endTime, tick), vehicleId), self))
      )
    case Event(
          TriggerWithId(AlightVehicleTrigger(tick, vehicleId, _), triggerId),
          data @ LiterallyDrivingData(_, _, _)
        ) =>
      val currentLeg = data.passengerSchedule.schedule.keys.view
        .drop(data.currentLegPassengerScheduleIndex)
        .headOption
        .getOrElse(throw new RuntimeException("Current Leg is not available."))
      stay() replying CompletionNotice(
        triggerId,
        Vector(
          ScheduleTrigger(AlightVehicleTrigger(Math.max(currentLeg.endTime + 1, tick), vehicleId), self)
        )
      )
    case _ @Event(EndingRefuelSession(tick, vehicleId, stall, _), _) =>
      log.debug(s"DrivesVehicle: EndingRefuelSession. tick: $tick, vehicle: $vehicleId, stall: $stall")
      stay()
  }

  private def hasRoomFor(
    passengerSchedule: PassengerSchedule,
    req: ReservationRequest,
    vehicle: BeamVehicle
  ) = {
    //    val vehicleCap = vehicle.getType
    val fullCap = vehicle.beamVehicleType.seatingCapacity + vehicle.beamVehicleType.standingRoomCapacity
    passengerSchedule.schedule.from(req.departFrom).to(req.arriveAt).forall { entry =>
      entry._2.riders.size < fullCap
    }
  }

  private def toll(leg: BeamLeg) = {
    if (leg.mode == BeamMode.CAR)
      tollCalculator.calcTollByLinkIds(leg.travelPath)
    else
      0.0
  }
}
