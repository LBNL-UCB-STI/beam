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

  private val interruptIdToModifyPassengerScheduleStatus =
    mutable.Map[Id[Interrupt], RideHailModifyPassengerScheduleStatus]()
  private val vehicleIdToModifyPassengerScheduleStatus =
    mutable.Map[Id[Vehicle], RideHailModifyPassengerScheduleStatus]()
  var allTriggersInWave: Vector[ScheduleTrigger] = Vector()
  var ignoreErrorPrint = false

  // We can change this to be Set[Id[Vehicle]], but then in case of terminated actor, we have to map it back to Id[Vehicle]
  //
  var waitingToReposition: Set[ActorRef] = Set.empty

  def setRepositioningsToProcess(toReposition: Set[ActorRef]): Unit = {
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
        reply.interruptId
      )
      cancelRepositionAttempt(rideHailAgent)
      clearModifyStatusFromCacheWithInterruptId(reply.interruptId)
    } else {
      interruptIdToModifyPassengerScheduleStatus.get(reply.interruptId) match {
        case repositionStatus @ Some(RideHailModifyPassengerScheduleStatus(_,_,_,Reposition,_,_,InterruptSent)) =>
          // Success! Continue with reposition process
          sendModifyPassengerScheduleMessage(
            repositionStatus.get,
            reply.isInstanceOf[InterruptedWhileDriving]
          )
        case reservationStatus @ Some(RideHailModifyPassengerScheduleStatus(_,_,_,SingleReservation,_,_,InterruptSent)) =>
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
          } else {
            // Success! Continue with reservation process
            sendModifyPassengerScheduleMessage(
              reservationStatus.get,
              reply.isInstanceOf[InterruptedWhileDriving]
            )
          }
        case _ =>
          log.error(
            "RideHailModifyPassengerScheduleManager- interruptId not found: interruptId {},interruptedPassengerSchedule {}, vehicle {}, tick {}",
            reply.interruptId,
            if(reply.isInstanceOf[InterruptedWhileDriving]){reply.asInstanceOf[InterruptedWhileDriving].passengerSchedule}else{"NA"},
            reply.vehicleId,
            reply.tick
          )
          cancelRepositionAttempt(rideHailAgent)
      }
    }
  }

  private def sendModifyPassengerScheduleMessage(
    modifyStatus: RideHailModifyPassengerScheduleStatus,
    stopDriving: Boolean
  ): Unit = {
    if (stopDriving) modifyStatus.rideHailAgent.tell(StopDriving(modifyStatus.tick), rideHailManagerRef)
    modifyStatus.rideHailAgent.tell(modifyStatus.modifyPassengerSchedule, rideHailManagerRef)
    modifyStatus.rideHailAgent.tell(Resume(), rideHailManagerRef)
    interruptIdToModifyPassengerScheduleStatus.put(modifyStatus.interruptId,modifyStatus.copy(status = ModifyPassengerScheduleSent))
    vehicleIdToModifyPassengerScheduleStatus.put(modifyStatus.vehicleId,modifyStatus.copy(status = ModifyPassengerScheduleSent))
  }

  def cancelRepositionAttempt(agentToRemove: ActorRef): Unit = {
    repositioningFinished(agentToRemove)
  }

  def repositioningFinished(agentToRemove: ActorRef): Unit = {
    if (waitingToReposition.contains(agentToRemove)) {
      waitingToReposition = waitingToReposition - agentToRemove
      checkIfRoundOfRepositioningIsDone()
    }else{
      log.error("Not found in waitingToReposition: {}", agentToRemove)
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
    if (triggersToSchedule.nonEmpty) {
      allTriggersInWave = triggersToSchedule ++ allTriggersInWave
    }
    repositioningFinished(rideHailManager.vehicleManager.getRideHailAgentLocation(vehicleId).rideHailAgent)
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
    if (!isPendingReservation(vehicleId)) {
      val rideHailModifyPassengerScheduleStatus = RideHailModifyPassengerScheduleStatus(
        RideHailModifyPassengerScheduleManager.nextRideHailAgentInterruptId,
        vehicleId,
        modifyPassengerSchedule,
        interruptOrigin,
        tick,
        rideHailAgent,
        InterruptSent
      )
      //log.debug("RideHailModifyPassengerScheduleManager- sendInterruptMessage: " + rideHailModifyPassengerScheduleStatus)
      saveModifyStatusInCache(rideHailModifyPassengerScheduleStatus)
      sendInterruptMessage(rideHailModifyPassengerScheduleStatus)
    } else {
      cancelRepositionAttempt(rideHailAgent)
      log.debug(
        "RideHailModifyPassengerScheduleManager- message ignored as repositioning cannot overwrite reserve: {}",
        vehicleId
      )
    }
  }

  def checkInResource(
                       vehicleId: Id[Vehicle],
                       availableIn: Option[SpaceTime],
                       passengerSchedule: Option[PassengerSchedule]
                     ): Unit = {
    passengerSchedule match {
      case Some(schedule) =>
        vehicleIdToModifyPassengerScheduleStatus.get(vehicleId).foreach{ status =>
          if (availableIn.get.time > 0) {
            val beamLeg =
              status.modifyPassengerSchedule.updatedPassengerSchedule.schedule.toVector.last._1
            val passengerScheduleLastLeg = schedule.schedule.toVector.last._1

            if (beamLeg.endTime != passengerScheduleLastLeg.endTime && status.interruptOrigin == SingleReservation) {
              // ignore, because this checkin is for a reposition and not the current Reservation
              log.debug(
                "checkin is not for current vehicle:" + status + ";checkInAt:" + availableIn
              )
            } else {
              interruptIdToModifyPassengerScheduleStatus.remove(status.interruptId)
              vehicleIdToModifyPassengerScheduleStatus.remove(vehicleId)
            }
          }
        }
      case None =>
      //        log.debug("checkin: {} with empty passenger schedule", vehicleId)
    }
  }

  private def saveModifyStatusInCache(
    rideHailModifyPassengerScheduleStatus: RideHailModifyPassengerScheduleStatus
  ): Unit = {
    interruptIdToModifyPassengerScheduleStatus.put(rideHailModifyPassengerScheduleStatus.interruptId, rideHailModifyPassengerScheduleStatus)
    vehicleIdToModifyPassengerScheduleStatus.put(rideHailModifyPassengerScheduleStatus.vehicleId,rideHailModifyPassengerScheduleStatus)
  }

  private def clearModifyStatusFromCacheWithInterruptId(
    interruptId: Id[Interrupt]
  ): Unit = {
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
    vehicleIdToModifyPassengerScheduleStatus.get(vehicleId).map(stat => stat.interruptOrigin == SingleReservation && stat.modifyPassengerSchedule.updatedPassengerSchedule == passengerSchedule ).getOrElse(false)
  }

  def isVehicleNeitherRepositioningNorProcessingReservation(vehicleId: Id[Vehicle]): Boolean = {
    !vehicleIdToModifyPassengerScheduleStatus.contains(vehicleId)
  }

  def printState(): Unit = {
    if (log.isDebugEnabled) {
      log.debug("printState START")
      vehicleIdToModifyPassengerScheduleStatus.foreach { x =>
        log.debug("vehicleIdModify: {} -> {}", x._1, x._2)
      }
      interruptIdToModifyPassengerScheduleStatus.foreach { x =>
        log.debug("interruptId: {} -> {}", x._1, x._2)
      }
      log.debug("printState END")
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

case class RideHailModifyPassengerScheduleStatus(
  interruptId: Id[Interrupt],
  vehicleId: Id[Vehicle],
  modifyPassengerSchedule: ModifyPassengerSchedule,
  interruptOrigin: InterruptOrigin,
  tick: Int,
  rideHailAgent: ActorRef,
  status: InterruptMessageStatus
)

case class ReduceAwaitingRepositioningAckMessagesByOne(toRemove: ActorRef)

object RideHailModifyPassengerScheduleManager {

  def nextRideHailAgentInterruptId: Id[Interrupt] = {
    Id.create(UUIDGen.createTime(UUIDGen.newTime()).toString, classOf[Interrupt])
  }
}
