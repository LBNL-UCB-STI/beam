package beam.agentsim.agents.ridehail

import akka.actor.ActorRef
import akka.event.LoggingAdapter
import beam.agentsim.agents.HasTickAndTrigger
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.StopDriving
import beam.agentsim.agents.ridehail.RideHailAgent._
import beam.agentsim.agents.ridehail.RideHailManager.{BufferedRideHailRequestsTrigger, RideHailRepositioningTrigger}
import beam.agentsim.agents.vehicles.{BeamVehicle, PassengerSchedule}
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.sim.config.BeamConfig
import beam.utils.InterruptIdIdGenerator
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

  private val interruptIdToModifyPassengerScheduleStatus =
    mutable.Map[Int, RideHailModifyPassengerScheduleStatus]()
  private val vehicleIdToModifyPassengerScheduleStatus =
    mutable.Map[Id[BeamVehicle], RideHailModifyPassengerScheduleStatus]()
  private val interruptedVehicleIds = mutable.Set[Id[Vehicle]]() // For debug only
  var allTriggersInWave: Vector[ScheduleTrigger] = Vector()
  var ignoreErrorPrint = false
  var numInterruptRepliesPending: Int = 0

  // We can change this to be Set[Id[Vehicle]], but then in case of terminated actor, we have to map it back to Id[Vehicle]
  //
  var waitingToReposition: Set[Id[BeamVehicle]] = Set.empty

  def setRepositioningsToProcess(toReposition: Set[Id[BeamVehicle]]): Unit = {
    waitingToReposition = toReposition
  }

  /*
   * This is the core of all the handling happening in this manager
   */
  def handleInterruptReply(reply: InterruptReply): Unit = {
    interruptIdToModifyPassengerScheduleStatus.get(reply.interruptId) match {
      case Some(status) =>
        interruptIdToModifyPassengerScheduleStatus.put(reply.interruptId, status.copy(interruptReply = Some(reply)))
        vehicleIdToModifyPassengerScheduleStatus.put(reply.vehicleId, status.copy(interruptReply = Some(reply)))
        interruptedVehicleIds.remove(reply.vehicleId)
        numInterruptRepliesPending = numInterruptRepliesPending - 1
        status.interruptOrigin match {
          case SingleReservation =>
            sendNewPassengerScheduleToVehicle(
              status.modifyPassengerSchedule.updatedPassengerSchedule,
              status.vehicleId,
              status.rideHailAgent,
              status.tick,
              status.modifyPassengerSchedule.reservationRequestId
            )
          case _ =>
        }
      case _ =>
        log.error(
          "RideHailModifyPassengerScheduleManager- interruptId not found: interruptId {},interruptedPassengerSchedule {}, vehicle {}, tick {}",
          reply.interruptId,
          if (reply.isInstanceOf[InterruptedWhileDriving]) {
            reply.asInstanceOf[InterruptedWhileDriving].passengerSchedule
          } else { "NA" },
          reply.vehicleId,
          reply.tick
        )
    }
  }
  def allInterruptConfirmationsReceived = numInterruptRepliesPending == 0

  private def sendModifyPassengerScheduleMessage(
    modifyStatus: RideHailModifyPassengerScheduleStatus,
    stopDriving: Boolean
  ): Unit = {
    if (stopDriving) {
      modifyStatus.rideHailAgent.tell(StopDriving(modifyStatus.tick), rideHailManagerRef)
    }
    modifyStatus.rideHailAgent.tell(modifyStatus.modifyPassengerSchedule, rideHailManagerRef)
    log.debug("sending Resume from sendModifyPassengerScheduleMessage to {}", modifyStatus.vehicleId)
    modifyStatus.rideHailAgent.tell(Resume, rideHailManagerRef)
    interruptIdToModifyPassengerScheduleStatus.put(
      modifyStatus.interruptId,
      modifyStatus.copy(status = ModifyPassengerScheduleSent)
    )
    vehicleIdToModifyPassengerScheduleStatus.put(
      modifyStatus.vehicleId,
      modifyStatus.copy(status = ModifyPassengerScheduleSent)
    )
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
      log.debug("Cleaning up from checkIfRoundOfRepositioningIsDone")
      sendCompletionAndScheduleNewTimeout(Reposition, 0)
      rideHailManager.cleanUp
    }
  }

  def modifyPassengerScheduleAckReceived(
    vehicleId: Id[Vehicle],
    triggersToSchedule: Vector[BeamAgentScheduler.ScheduleTrigger],
    tick: Int
  ): Unit = {
    clearModifyStatusFromCacheWithVehicleId(vehicleId)
    if (triggersToSchedule.nonEmpty) {
      allTriggersInWave = triggersToSchedule ++ allTriggersInWave
    }
    repositioningFinished(vehicleId)
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
          currentTick + beamConfig.beam.agentsim.agents.rideHail.repositioningManager.timeout
        )
      case _ =>
        throw new RuntimeException("Should not attempt to send completion when doing single reservations")
    }
    if (allTriggersInWave.size > 0)
      rideHailManager.log.debug(
        "Earliest tick in triggers to schedule is {} and latest is {}",
        allTriggersInWave.map(_.trigger.tick).min,
        allTriggersInWave.map(_.trigger.tick).max
      )
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
    //    assert(numberPendingModifyPassengerScheduleAcks <= 0)
    rideHailManager.vehicleManager.getIdleAndInServiceVehicles.foreach { veh =>
      sendInterruptMessage(
        ModifyPassengerSchedule(PassengerSchedule(), tick),
        tick,
        veh._1,
        veh._2.rideHailAgent,
        HoldForPlanning
      )
    }
    numInterruptRepliesPending = rideHailManager.vehicleManager.getIdleAndInServiceVehicles.size
    holdTickAndTriggerId(tick, triggerId)
  }

  def sendNewPassengerScheduleToVehicle(
    passengerSchedule: PassengerSchedule,
    rideHailVehicleId: Id[Vehicle],
    rideHailAgentRef: ActorRef,
    tick: Int,
    reservationRequestIdOpt: Option[Int] = None
  ): Unit = {
    vehicleIdToModifyPassengerScheduleStatus.get(rideHailVehicleId) match {
      case Some(status) =>
        val reply = status.interruptReply.get
        val isRepositioning = waitingToReposition.nonEmpty
        interruptIdToModifyPassengerScheduleStatus.get(reply.interruptId) match {
          case Some(
              RideHailModifyPassengerScheduleStatus(
                _,
                _,
                _,
                _,
                _,
                _,
                rideHailAgentRef,
                InterruptSent
              )
              ) =>
            reply match {
              case InterruptedWhileOffline(_, _, _) if isRepositioning =>
                log.debug(
                  "Cancelling repositioning for {} because {}, interruptId {}, numberPendingModifyPassengerScheduleAcks {}",
                  reply.vehicleId,
                  reply.getClass.getCanonicalName,
                  reply.interruptId
                )
                cancelRepositionAttempt(reply.vehicleId)
                log.debug(
                  "sending Resume from sendNewPassengerScheduleToVehicle when repositioning to {}",
                  reply.vehicleId
                )
                rideHailAgentRef ! Resume
                clearModifyStatusFromCacheWithInterruptId(reply.interruptId)
              case InterruptedWhileOffline(_, _, _) =>
                log.debug(
                  "Abandoning attempt to modify passenger schedule of vehicle {} @ {} because {}",
                  reply.vehicleId,
                  reply.tick,
                  reply.getClass.getCanonicalName
                )
                val requestIdOpt = interruptIdToModifyPassengerScheduleStatus(reply.interruptId).modifyPassengerSchedule.reservationRequestId
                val requestId = requestIdOpt match {
                  case Some(_) => requestIdOpt
                  case None    => reservationRequestIdOpt
                }
                log.debug("sending Resume from sendNewPassengerScheduleToVehicle to {}", reply.vehicleId)
                rideHailAgentRef ! Resume
                clearModifyStatusFromCacheWithInterruptId(reply.interruptId)
                if (requestId.isDefined) {
                  rideHailManager.cancelReservationDueToFailedModifyPassengerSchedule(requestId.get)
                  //              if (rideHailManager.cancelReservationDueToFailedModifyPassengerSchedule(requestId.get)) {
                  //                log.debug(
                  //                  "sendCompletionAndScheduleNewTimeout from line 100 @ {} with trigger {}",
                  //                  _currentTick,
                  //                  _currentTriggerId
                  //                )
                  //                if (rideHailManager.processBufferedRequestsOnTimeout) {
                  //                  rideHailManager.cleanUpBufferedRequestProcessing(_currentTick.get)
                  //                }
                  //              }
                }
              case _ =>
                // Success! Continue with modify process
                log.debug(
                  "RideHailModifyPassengerScheduleManager - modifying pass schedule of: " + rideHailVehicleId
                )
                sendModifyPassengerScheduleMessage(
                  status.copy(
                    modifyPassengerSchedule = status.modifyPassengerSchedule
                      .copy(
                        updatedPassengerSchedule = passengerSchedule,
                        reservationRequestId = reservationRequestIdOpt
                      )
                  ),
                  reply.isInstanceOf[InterruptedWhileDriving]
                )
            }
          case _ =>
            log.error(
              "RideHailModifyPassengerScheduleManager- interruptId not found: interruptId {},interruptedPassengerSchedule {}, vehicle {}, tick {}",
              reply.interruptId,
              if (reply.isInstanceOf[InterruptedWhileDriving]) {
                reply.asInstanceOf[InterruptedWhileDriving].passengerSchedule
              } else { "NA" },
              reply.vehicleId,
              reply.tick
            )
            cancelRepositionAttempt(reply.vehicleId)
        }
      case None =>
        // This is a non-buffered modify scenario, we still need to send Interrupt
        sendInterruptMessage(
          ModifyPassengerSchedule(passengerSchedule, tick, reservationRequestIdOpt),
          tick,
          rideHailVehicleId,
          rideHailAgentRef,
          SingleReservation
        )
    }
  }

  private def sendInterruptMessage(
    modifyPassengerSchedule: ModifyPassengerSchedule,
    tick: Int,
    vehicleId: Id[Vehicle],
    rideHailAgent: ActorRef,
    interruptOrigin: InterruptOrigin
  ): Unit = {
    if (!isPendingReservation(vehicleId)) {
      val rideHailModifyPassengerScheduleStatus = RideHailModifyPassengerScheduleStatus(
        RideHailModifyPassengerScheduleManager.nextRideHailAgentInterruptId,
        vehicleId,
        modifyPassengerSchedule,
        interruptOrigin,
        None,
        tick,
        rideHailAgent,
        InterruptSent
      )
      log.debug(
        "RideHailModifyPassengerScheduleManager- sendInterrupt:  " + rideHailModifyPassengerScheduleStatus.interruptId
      )
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
    vehicleIdToModifyPassengerScheduleStatus.put(
      rideHailModifyPassengerScheduleStatus.vehicleId,
      rideHailModifyPassengerScheduleStatus
    )
    interruptedVehicleIds.add(rideHailModifyPassengerScheduleStatus.vehicleId)
  }

  def setStatusToIdle(vehicleId: Id[BeamVehicle]) = {
    vehicleIdToModifyPassengerScheduleStatus.get(vehicleId) match {
      case Some(status) =>
        val newStatus =
          status.copy(interruptReply = Some(InterruptedWhileIdle(status.interruptId, vehicleId, status.tick)))
        vehicleIdToModifyPassengerScheduleStatus.put(vehicleId, newStatus)
        interruptIdToModifyPassengerScheduleStatus.put(status.interruptId, newStatus)
      case None =>
    }
  }

  def cleanUpCaches = {
    interruptIdToModifyPassengerScheduleStatus.values.foreach { status =>
      status.status match {
        case ModifyPassengerScheduleSent =>
        case _ =>
          log.debug("sending Resume from cleanUpCaches to {}", status.vehicleId)
          status.rideHailAgent.tell(Resume, rideHailManagerRef)
      }
    }
    vehicleIdToModifyPassengerScheduleStatus.clear
    interruptIdToModifyPassengerScheduleStatus.clear
    interruptedVehicleIds.clear
  }

  def clearModifyStatusFromCacheWithVehicleId(vehicleId: Id[Vehicle]): Unit = {
    vehicleIdToModifyPassengerScheduleStatus.remove(vehicleId).foreach { status =>
      interruptIdToModifyPassengerScheduleStatus.remove(status.interruptId)
      log.debug("remove interrupt from clearModifyStatusFromCacheWithVehicleId {}", status.interruptId)
    }
  }
  private def clearModifyStatusFromCacheWithInterruptId(
    interruptId: Int
  ): Unit = {
    log.debug("remove interrupt from clearModifyStatusFromCacheWithInterruptId {}", interruptId)
    interruptIdToModifyPassengerScheduleStatus.remove(interruptId).foreach { rideHailModifyPassengerScheduleStatus =>
      vehicleIdToModifyPassengerScheduleStatus.remove(rideHailModifyPassengerScheduleStatus.vehicleId)
    }
  }

  def isPendingReservation(vehicleId: Id[Vehicle]): Boolean = {
    vehicleIdToModifyPassengerScheduleStatus.get(vehicleId).map(_.interruptOrigin == SingleReservation).getOrElse(false)
  }

  private def sendInterruptMessage(
    passengerScheduleStatus: RideHailModifyPassengerScheduleStatus
  ): Unit = {
    //    log.debug("sendInterruptMessage:" + passengerScheduleStatus)
    passengerScheduleStatus.rideHailAgent
      .tell(Interrupt(passengerScheduleStatus.interruptId, passengerScheduleStatus.tick), rideHailManagerRef)
  }

  def doesPendingReservationContainPassSchedule(
    vehicleId: Id[Vehicle],
    passengerSchedule: PassengerSchedule
  ): Boolean = {
    vehicleIdToModifyPassengerScheduleStatus
      .get(vehicleId)
      .map(
        stat =>
          stat.interruptOrigin == SingleReservation && stat.modifyPassengerSchedule.updatedPassengerSchedule == passengerSchedule
      )
      .getOrElse(false)
  }

  def isVehicleNeitherRepositioningNorProcessingReservation(vehicleId: Id[Vehicle]): Boolean = {
    // FIXME `vehicleIdToModifyPassengerScheduleStatus` is broken, so for now we return `true`, but fixme, please!
    // !vehicleIdToModifyPassengerScheduleStatus.contains(vehicleId)
    true
  }

  def isModifyStatusCacheEmpty: Boolean = interruptIdToModifyPassengerScheduleStatus.isEmpty

  def printState(): Unit = {
    if (log.isDebugEnabled) {
//      log.debug("printState START")
//      vehicleIdToModifyPassengerScheduleStatus.foreach { x =>
//        log.debug("vehicleIdModify: {} -> {}", x._1, x._2)
//      }
//      interruptIdToModifyPassengerScheduleStatus.foreach { x =>
//        log.debug("interruptId: {} -> {}", x._1, x._2)
//      }
//      log.debug("printState END")
    }
  }

}

sealed trait InterruptMessageStatus
case object InterruptSent extends InterruptMessageStatus
case object ModifyPassengerScheduleSent extends InterruptMessageStatus

sealed trait BatchDispatchType
trait InterruptOrigin extends BatchDispatchType
case object BatchedReservation extends InterruptOrigin
case object SingleReservation extends InterruptOrigin
case object Reposition extends InterruptOrigin
case object HoldForPlanning extends InterruptOrigin

case class RideHailModifyPassengerScheduleStatus(
  interruptId: Int,
  vehicleId: Id[Vehicle],
  modifyPassengerSchedule: ModifyPassengerSchedule,
  interruptOrigin: InterruptOrigin,
  interruptReply: Option[InterruptReply],
  tick: Int,
  rideHailAgent: ActorRef,
  status: InterruptMessageStatus
)

case class ReduceAwaitingRepositioningAckMessagesByOne(vehicleId: Id[Vehicle])

object RideHailModifyPassengerScheduleManager {
  def nextRideHailAgentInterruptId: Int = InterruptIdIdGenerator.nextId
}
