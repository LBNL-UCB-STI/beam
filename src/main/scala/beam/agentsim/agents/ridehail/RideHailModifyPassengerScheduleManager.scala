package beam.agentsim.agents.ridehail

import akka.actor.ActorRef
import akka.event.LoggingAdapter
import beam.agentsim.agents.HasTickAndTrigger
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.StopDriving
import beam.agentsim.agents.ridehail.RideHailAgent._
import beam.agentsim.agents.ridehail.RideHailManager.{BufferedRideHailRequestsTrigger, RideHailRepositioningTrigger}
import beam.agentsim.agents.ridehail.RideHailVehicleManager.RideHailAgentLocation
import beam.agentsim.agents.vehicles.PassengerSchedule
import beam.agentsim.events.SpaceTime
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.sim.config.BeamConfig
import beam.utils.DebugLib
import com.eaio.uuid.UUIDGen
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

import scala.collection.mutable

class RideHailModifyPassengerScheduleManager(
  val log: LoggingAdapter,
  val rideHailManagerRef: ActorRef,
  val rideHailManager: RideHailManager,
  val scheduler: ActorRef,
  val beamConfig: BeamConfig
) extends HasTickAndTrigger {

  val resourcesNotCheckedIn_onlyForDebugging: mutable.Set[Id[Vehicle]] = mutable.Set()
  private val interruptIdToModifyPassengerScheduleStatus =
    mutable.Map[Id[Interrupt], RideHailModifyPassengerScheduleStatus]()
  private val vehicleIdToModifyPassengerScheduleStatus =
    mutable.Map[Id[Vehicle], mutable.ListBuffer[RideHailModifyPassengerScheduleStatus]]()
  var allTriggersInWave: Vector[ScheduleTrigger] = Vector()
  var numberPendingModifyPassengerScheduleAcks: Int = 0
  var ignoreErrorPrint = false

  // We can change this to be Set[Id[Vehicle]], but then in case of terminated actor, we have to map it back to Id[Vehicle]
  //
  var waitingToReposition: Set[Id[Vehicle]] = Set.empty

  def setRepositioningsToProcess(toReposition: Set[Id[Vehicle]]): Unit = {
    waitingToReposition = toReposition
  }

  /*
   * This is the core of all the handling happening in this manager
   */
  def handleInterruptReply(reply: InterruptReply): Unit = {
    val rideHailAgent = rideHailManager.vehicleManager.getRideHailAgentLocation(reply.vehicleId).rideHailAgent
    val isRepositioning = waitingToReposition.nonEmpty
    // This code should be executed only in case of random repositioning. If `waitingToReposition` is not empty, so it is the case!
    if (reply.isInstanceOf[InterruptedWhileOffline] && isRepositioning) {
      log.debug(
        "Cancelling repositioning for {}, interruptId {}, numberPendingModifyPassengerScheduleAcks {}",
        reply.vehicleId,
        reply.interruptId,
        numberPendingModifyPassengerScheduleAcks
      )
      cancelRepositionAttempt(reply.vehicleId)
      clearModifyStatusFromCacheWithInterruptId(reply.interruptId)
    } else {
      interruptIdToModifyPassengerScheduleStatus.get(reply.interruptId) match {
        case None =>
          log.error(
            "RideHailModifyPassengerScheduleManager- interruptId not found: interruptId {}, vehicle {}, tick {}, reply {}",
            reply.interruptId,
            reply.vehicleId,
            reply.tick,
            reply
          )
          cancelRepositionAttempt(reply.vehicleId)
        case Some(modifyStatus) =>
          assert(reply.vehicleId == modifyStatus.vehicleId)
          assert(reply.tick == modifyStatus.tick)

          val allModifyStatusesForVehicle = getModifyStatusListForId(reply.vehicleId)
          if (allModifyStatusesForVehicle.isEmpty && log.isErrorEnabled) {
            log.error(
              "allModifyStatusesForVehicle.isEmpty, modifyStatus: {}",
              modifyStatus
            )
          } else {
            val reservationModifyStatuses = allModifyStatusesForVehicle.filter(_.interruptOrigin == SingleReservation)

            if (reservationModifyStatuses.isEmpty) {
              // Success! Continue with reposition process
              sendModifyPassengerScheduleMessage(
                allModifyStatusesForVehicle.last,
                reply.isInstanceOf[InterruptedWhileDriving]
              )
            } else if (reservationModifyStatuses.size == 1) {
              modifyStatus.interruptOrigin match {
                case Reposition =>
                  // detected race condition with reservation interrupt: if message coming back is reposition message interrupt, then the interrupt confirmation for reservation message is on
                  // its way - wait on that and count this reposition as completed.
                  cancelRepositionAttempt(reply.vehicleId)
                  clearModifyStatusFromCacheWithInterruptId(reply.interruptId)

                  /* We are overwriting a reposition with a reservation, if the driver was interrupted while driving,
                we send a resume message to the agent. This puts the driver back to state driving, so that the reservation
                interrupt is received when the agent is in state driving. */
                  if (reply.isInstanceOf[InterruptedWhileDriving]) {
                    modifyStatus.rideHailAgent.tell(Resume(), rideHailManagerRef)
                  }
                case SingleReservation =>
                  // process reservation interrupt confirmation
                  val reservationStatus = reservationModifyStatuses.head
                  assert(
                    reservationStatus.status != InterruptMessageStatus.UNDEFINED,
                    "reservation message should not be undefined but at least should have sent out interrupt"
                  )
                  if (reply.isInstanceOf[InterruptedWhileOffline]) {
                    // Oops, tried to reserve this vehicle before knowing it was unavailable
                    log.debug(
                      s"Abandoning attempt to modify passenger schedule of vehilce ${reply.vehicleId} @ ${reply.tick}"
                    )
                    val requestId = interruptIdToModifyPassengerScheduleStatus
                      .remove(reply.interruptId)
                      .get
                      .modifyPassengerSchedule
                      .reservationRequestId
                      .get
                    if (rideHailManager.cancelReservationDueToFailedModifyPassengerSchedule(requestId)) {
                      log.debug(
                        "sendCompletionAndScheduleNewTimeout from line 100 @ {} with trigger {}",
                        _currentTick,
                        _currentTriggerId
                      )
                      if (rideHailManager.processBufferedRequestsOnTimeout) {
                        sendCompletionAndScheduleNewTimeout(BatchedReservation, _currentTick.get)
                        rideHailManager.cleanUp
                      }
                    }
                  } else if (reservationStatus.status == InterruptMessageStatus.INTERRUPT_SENT) {
                    // Success! Continue with reservation process
                    sendModifyPassengerScheduleMessage(
                      reservationStatus,
                      reply.isInstanceOf[InterruptedWhileDriving]
                    )
                  } else
                    log.error("RideHailModifyPassengerScheduleManager - unexpected interrupt message")
              }
            } else if (reservationModifyStatuses.size > 1 && log.isErrorEnabled) {
              val str =
                reservationModifyStatuses
                  .map(a => "reservation requests:" + a.toString)
                  .mkString(System.lineSeparator())
              log.error(
                "RideHailModifyPassengerScheduleManager - reservationModifyStatuses contains more than one rideHail reservation request for same vehicle({}) {}",
                reply.vehicleId,
                str
              )
            }
          }
      }
    }
  }

  private def sendModifyPassengerScheduleMessage(
    modifyStatus: RideHailModifyPassengerScheduleStatus,
    stopDriving: Boolean
  ): Unit = {
    if (stopDriving) modifyStatus.rideHailAgent.tell(StopDriving(modifyStatus.tick.toInt), rideHailManagerRef)
    resourcesNotCheckedIn_onlyForDebugging += modifyStatus.vehicleId
    modifyStatus.rideHailAgent.tell(modifyStatus.modifyPassengerSchedule, rideHailManagerRef)
    modifyStatus.rideHailAgent.tell(Resume(), rideHailManagerRef)
    modifyStatus.status = InterruptMessageStatus.MODIFY_PASSENGER_SCHEDULE_SENT
  }

  def cancelRepositionAttempt(vehicleId: Id[Vehicle]): Unit = {
    repositioningFinished(vehicleId)
  }

  def repositioningFinished(vehicleId: Id[Vehicle]): Unit = {
    if (waitingToReposition.contains(vehicleId)) {
      waitingToReposition = waitingToReposition - vehicleId
      checkIfRoundOfRepositioningIsDone()
    } else {
      log.error("Not found in waitingToReposition: {}", vehicleId)
    }
  }

  def checkIfRoundOfRepositioningIsDone(): Unit = {
    if (waitingToReposition.isEmpty) {
      sendCompletionAndScheduleNewTimeout(Reposition, 0)
      rideHailManager.cleanUp
    }
  }

  def modifyPassengerScheduleAckReceived(
    vehicleId: Id[Vehicle],
    triggersToSchedule: Vector[BeamAgentScheduler.ScheduleTrigger],
    tick: Int
  ): Unit = {
    // Following is just error checking
    if (triggersToSchedule.nonEmpty) {
      val vehicleId: Id[Vehicle] = Id.create(
        triggersToSchedule.head.agent.path.name.replace("rideHailAgent", "rideHailVehicle"),
        classOf[Vehicle]
      )
      val modifyStatusList = getModifyStatusListForId(vehicleId)
      if (modifyStatusList.size > 2 && ignoreErrorPrint) {
        log.error(
          "more rideHailVehicle interruptions in process than should be possible: {} -> further errors supressed (debug later if this is still relevant)",
          vehicleId
        )
        ignoreErrorPrint = true
      }
      if (modifyStatusList.size > 1 && modifyStatusList.exists(_.interruptOrigin == SingleReservation)) {
        // this means there is a race condition between a repositioning and reservation message and we should remove the reposition/not process it further
        // ALREADY removed in handle interruption
        log.debug("reposition and reservation race condition detected:" + vehicleId)
        log.debug("modifyStatusList: " + modifyStatusList.toString())
      }

      allTriggersInWave = triggersToSchedule ++ allTriggersInWave
    }
    repositioningFinished(vehicleId)
  }

  def getModifyStatusListForId(
    vehicleId: Id[Vehicle]
  ): mutable.ListBuffer[RideHailModifyPassengerScheduleStatus] = {
    if (!vehicleIdToModifyPassengerScheduleStatus.contains(vehicleId)) {
      vehicleIdToModifyPassengerScheduleStatus.put(
        vehicleId,
        mutable.ListBuffer[RideHailModifyPassengerScheduleStatus]()
      )
    }
    vehicleIdToModifyPassengerScheduleStatus(vehicleId)
  }

  def sendCompletionAndScheduleNewTimeout(batchDispatchType: BatchDispatchType, tick: Int): Unit = {
    val (currentTick, triggerId) = releaseTickAndTriggerId()
    val timerTrigger = batchDispatchType match {
      case BatchedReservation =>
        BufferedRideHailRequestsTrigger(
          currentTick + beamConfig.beam.agentsim.agents.rideHail.allocationManager.requestBufferTimeoutInSeconds
        )
      case Reposition =>
        RideHailRepositioningTrigger(
          currentTick + beamConfig.beam.agentsim.agents.rideHail.allocationManager.repositionTimeoutInSeconds
        )
      case _ =>
        throw new RuntimeException("Should not attempt to send completion when doing single reservations")
    }
    //    log.debug("complete at {} triggerID {} with {} triggers", currentTick, triggerId, allTriggersInWave.size)
    //      if (!allTriggersInWave.isEmpty) {
    //        log.debug(
    //          "triggers from {} to {}",
    //          allTriggersInWave.map(_.trigger.tick).min,
    //          allTriggersInWave.map(_.trigger.tick).max
    //        )
    //      }
    scheduler.tell(
      CompletionNotice(triggerId, allTriggersInWave :+ ScheduleTrigger(timerTrigger, rideHailManagerRef)),
      rideHailManagerRef
    )
    allTriggersInWave = Vector()
  }

  def addTriggerToSendWithCompletion(newTrigger: ScheduleTrigger) = {
    allTriggersInWave = allTriggersInWave :+ newTrigger
  }

  def addTriggersToSendWithCompletion(newTriggers: Vector[ScheduleTrigger]) = {
    allTriggersInWave = allTriggersInWave ++ newTriggers
  }

  def startWaveOfRepositioningOrBatchedReservationRequests(tick: Int, triggerId: Long): Unit = {
    assert(
      vehicleIdToModifyPassengerScheduleStatus.values.count(scheduleStatuses => scheduleStatuses.nonEmpty)
        == resourcesNotCheckedIn_onlyForDebugging.count(x => getModifyStatusListForId(x).nonEmpty)
    )
    //    assert(numberPendingModifyPassengerScheduleAcks <= 0)
    holdTickAndTriggerId(tick, triggerId)
  }

  def repositionVehicle(
    passengerSchedule: PassengerSchedule,
    tick: Int,
    vehicleId: Id[Vehicle],
    rideHailAgent: ActorRef
  ): Unit = {
    log.debug("RideHailModifyPassengerScheduleManager- repositionVehicle request: " + vehicleId)
    sendInterruptMessage(
      ModifyPassengerSchedule(passengerSchedule, tick),
      tick,
      vehicleId,
      rideHailAgent,
      Reposition
    )
  }

  def reserveVehicle(
    passengerSchedule: PassengerSchedule,
    rideHailAgent: RideHailAgentLocation,
    tick: Int,
    reservationRequestId: Option[Int]
  ): Unit = {
    log.debug(
      "RideHailModifyPassengerScheduleManager- reserveVehicle request: " + rideHailAgent.vehicleId
    )
    sendInterruptMessage(
      ModifyPassengerSchedule(passengerSchedule, tick, reservationRequestId),
      passengerSchedule.schedule.head._1.startTime,
      rideHailAgent.vehicleId,
      rideHailAgent.rideHailAgent,
      SingleReservation
    )
  }

  private def sendInterruptMessage(
    modifyPassengerSchedule: ModifyPassengerSchedule,
    tick: Int,
    vehicleId: Id[Vehicle],
    rideHailAgent: ActorRef,
    interruptOrigin: InterruptOrigin
  ): Unit = {
    if (noPendingReservations(vehicleId)) {
      val rideHailModifyPassengerScheduleStatus = RideHailModifyPassengerScheduleStatus(
        RideHailModifyPassengerScheduleManager.nextRideHailAgentInterruptId,
        vehicleId,
        modifyPassengerSchedule,
        interruptOrigin,
        tick,
        rideHailAgent,
        InterruptMessageStatus.UNDEFINED
      )
      //log.debug("RideHailModifyPassengerScheduleManager- sendInterruptMessage: " + rideHailModifyPassengerScheduleStatus)
      saveModifyStatusInCache(rideHailModifyPassengerScheduleStatus)
      sendInterruptMessage(rideHailModifyPassengerScheduleStatus)
    } else {
      cancelRepositionAttempt(vehicleId)
      log.debug(
        "RideHailModifyPassengerScheduleManager- message ignored as repositioning cannot overwrite reserve: {}",
        vehicleId
      )
    }
  }

  private def saveModifyStatusInCache(
    rideHailModifyPassengerScheduleStatus: RideHailModifyPassengerScheduleStatus
  ): Unit = {
    interruptIdToModifyPassengerScheduleStatus.put(
      rideHailModifyPassengerScheduleStatus.interruptId,
      rideHailModifyPassengerScheduleStatus
    )
    getModifyStatusListForId(rideHailModifyPassengerScheduleStatus.vehicleId) += rideHailModifyPassengerScheduleStatus
  }

  private def clearModifyStatusFromCacheWithInterruptId(
    interruptId: Id[Interrupt]
  ): Unit = {
    interruptIdToModifyPassengerScheduleStatus.remove(interruptId).foreach { rideHailModifyPassengerScheduleStatus =>
      val set = vehicleIdToModifyPassengerScheduleStatus(
        rideHailModifyPassengerScheduleStatus.vehicleId
      )
      set -= rideHailModifyPassengerScheduleStatus
      Some(rideHailModifyPassengerScheduleStatus)
    }
  }

  def noPendingReservations(vehicleId: Id[Vehicle]): Boolean = {
    !getModifyStatusListForId(vehicleId).exists(_.interruptOrigin == SingleReservation)
  }

  private def sendInterruptMessage(
    passengerScheduleStatus: RideHailModifyPassengerScheduleStatus
  ): Unit = {
    resourcesNotCheckedIn_onlyForDebugging += passengerScheduleStatus.vehicleId
    passengerScheduleStatus.status = InterruptMessageStatus.INTERRUPT_SENT
    //    log.debug("sendInterruptMessage:" + passengerScheduleStatus)
    passengerScheduleStatus.rideHailAgent
      .tell(Interrupt(passengerScheduleStatus.interruptId, passengerScheduleStatus.tick), rideHailManagerRef)
  }

  def isPendingReservationEnding(
    vehicleId: Id[Vehicle],
    passengerSchedule: PassengerSchedule
  ): Boolean = {
    var result = false
    getModifyStatusListForId(vehicleId)
      .find(_.interruptOrigin == SingleReservation)
      .foreach { stats =>
        result = stats.modifyPassengerSchedule.updatedPassengerSchedule == passengerSchedule
      }

    result
  }

  def isVehicleNeitherRepositioningNorProcessingReservation(vehicleId: Id[Vehicle]): Boolean = {
    getModifyStatusListForId(vehicleId).isEmpty
  }

  def checkInResource(
    vehicleId: Id[Vehicle],
    availableIn: Option[SpaceTime],
    passengerSchedule: Option[PassengerSchedule]
  ): Unit = {
    passengerSchedule match {
      case Some(schedule) =>
        var rideHailModifyPassengerScheduleStatusSet = getModifyStatusListForId(vehicleId)
        var deleteItems = mutable.ListBuffer[RideHailModifyPassengerScheduleStatus]()
        //        log.debug(
        //          "BEFORE checkin.removeWithVehicleId({}):{}, passengerSchedule: {}",
        //          rideHailModifyPassengerScheduleStatusSet.size,
        //          rideHailModifyPassengerScheduleStatusSet,
        //          passengerSchedule
        //        )
        val listSizeAtStart = rideHailModifyPassengerScheduleStatusSet.size

        rideHailModifyPassengerScheduleStatusSet.foreach { status =>
          if (status.modifyPassengerSchedule.updatedPassengerSchedule == schedule) {
            assert(status.status == InterruptMessageStatus.MODIFY_PASSENGER_SCHEDULE_SENT)
            deleteItems += status
          }
        }

        assert(
          deleteItems.size <= 1,
          s"checkin: for $vehicleId the passenger schedule is ambigious and cannot be deleted"
        )

        // ====remove correct status message===
        if (deleteItems.size > 1) {
          // this means that multiple MODIFY_PASSENGER_SCHEDULE_SENT outstanding and we need to keep them in order
          deleteItems = deleteItems.splitAt(1)._1
        }

        deleteItems.foreach { status =>
          if (availableIn.get.time > 0) {
            val beamLeg =
              status.modifyPassengerSchedule.updatedPassengerSchedule.schedule.toVector.last._1
            val passengerScheduleLastLeg = schedule.schedule.toVector.last._1

            if (beamLeg.endTime != passengerScheduleLastLeg.endTime && status.interruptOrigin == SingleReservation) {
              // ignore, because this checkin is for a reposition and not the current Reservation
              log.debug(
                "checkin is not for current vehicle:" + status + ";checkInAt:" + availableIn
              )

              DebugLib.emptyFunctionForSettingBreakPoint()
            } else {
              interruptIdToModifyPassengerScheduleStatus.remove(status.interruptId)

              vehicleIdToModifyPassengerScheduleStatus.put(
                vehicleId,
                rideHailModifyPassengerScheduleStatusSet diff deleteItems
              )
              rideHailModifyPassengerScheduleStatusSet = getModifyStatusListForId(vehicleId)
              if (rideHailModifyPassengerScheduleStatusSet.isEmpty) {
                resourcesNotCheckedIn_onlyForDebugging.remove(vehicleId)
              }

              // only something new, if all undefined (no pending query)
              // TODO: double check if the following code will ever be executed as we are not buffering anymore resp. is it really needed and not handled somewhere else
              if (rideHailModifyPassengerScheduleStatusSet.nonEmpty && rideHailModifyPassengerScheduleStatusSet
                    .count(
                      _.status == InterruptMessageStatus.UNDEFINED
                    ) == rideHailModifyPassengerScheduleStatusSet.size) {
                sendInterruptMessage(rideHailModifyPassengerScheduleStatusSet.head)
              }
            }

          }
        }

        if (listSizeAtStart == rideHailModifyPassengerScheduleStatusSet.size) {
          DebugLib.emptyFunctionForSettingBreakPoint()
        }

      //        log.debug(
      //          "AFTER checkin.removeWithVehicleId({}):{}, passengerSchedule: {}",
      //          rideHailModifyPassengerScheduleStatusSet.size,
      //          rideHailModifyPassengerScheduleStatusSet,
      //          passengerSchedule
      //        )

      case None =>
      //        log.debug("checkin: {} with empty passenger schedule", vehicleId)
    }
  }

  def vehicleHasMoreThanOneOngoingRequests(vehicleId: Id[Vehicle]): Boolean = {
    getModifyStatusListForId(vehicleId).size > 1
  }

  def printState(): Unit = {
    if (log.isDebugEnabled) {
      log.debug("printState START")
      vehicleIdToModifyPassengerScheduleStatus.foreach { x =>
        log.debug("vehicleIdModify: {} -> {}", x._1, x._2)
      }
      resourcesNotCheckedIn_onlyForDebugging.foreach { x =>
        log.debug(
          "resource not checked in: {}-> getWithVehicleIds({}): {}",
          x.toString,
          getModifyStatusListForId(x).size,
          getModifyStatusListForId(x)
        )
      }
      interruptIdToModifyPassengerScheduleStatus.foreach { x =>
        log.debug("interruptId: {} -> {}", x._1, x._2)
      }
      log.debug("printState END")
    }
  }

}

object InterruptMessageStatus extends Enumeration {
  val UNDEFINED, INTERRUPT_SENT, MODIFY_PASSENGER_SCHEDULE_SENT, EXECUTED = Value
}

sealed trait BatchDispatchType
trait InterruptOrigin extends BatchDispatchType
case object BatchedReservation extends InterruptOrigin
case object SingleReservation extends InterruptOrigin
case object Reposition extends InterruptOrigin

case class RideHailModifyPassengerScheduleStatus(
  interruptId: Id[Interrupt],
  vehicleId: Id[Vehicle],
  modifyPassengerSchedule: ModifyPassengerSchedule,
  interruptOrigin: InterruptOrigin,
  tick: Int,
  rideHailAgent: ActorRef,
  var status: InterruptMessageStatus.Value = InterruptMessageStatus.UNDEFINED
)

case class ReduceAwaitingRepositioningAckMessagesByOne(vehicleId: Id[Vehicle])

object RideHailModifyPassengerScheduleManager {

  def nextRideHailAgentInterruptId: Id[Interrupt] = {
    Id.create(UUIDGen.createTime(UUIDGen.newTime()).toString, classOf[Interrupt])
  }
}
