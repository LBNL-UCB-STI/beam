package beam.agentsim.agents.ridehail

import akka.actor.ActorRef
import akka.event.LoggingAdapter
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.StopDriving
import beam.agentsim.agents.ridehail.RideHailAgent.{Interrupt, ModifyPassengerSchedule, Resume}
import beam.agentsim.agents.ridehail.RideHailManager.{RideHailAgentLocation, RideHailAllocationManagerTimeout}
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
  val rideHailManager: ActorRef,
  val rideHailAllocationManagerTimeoutInSeconds: Double,
  val scheduler: ActorRef,
  val beamConfig: BeamConfig
) {

  private val interruptIdToModifyPassengerScheduleStatus =
    mutable.Map[Id[Interrupt], RideHailModifyPassengerScheduleStatus]()
  private val vehicleIdToModifyPassengerScheduleStatus =
    mutable.Map[Id[Vehicle], mutable.ListBuffer[RideHailModifyPassengerScheduleStatus]]()
  var nextCompleteNoticeRideHailAllocationTimeout: Option[CompletionNotice] = None
  var numberOfOutStandingmodifyPassengerScheduleAckForRepositioning: Int = 0
  val resourcesNotCheckedIn_onlyForDebugging = mutable.Set[Id[Vehicle]]()

  private def add(
    rideHailModifyPassengerScheduleStatus: RideHailModifyPassengerScheduleStatus
  ): Unit = {
    interruptIdToModifyPassengerScheduleStatus.put(
      rideHailModifyPassengerScheduleStatus.interruptId,
      rideHailModifyPassengerScheduleStatus
    )
    addToVehicleInterruptIds(rideHailModifyPassengerScheduleStatus)
  }

  private def addToVehicleInterruptIds(
    rideHailModifyPassengerScheduleStatus: RideHailModifyPassengerScheduleStatus
  ): Unit = {
    var listBuffer = getWithVehicleIds(rideHailModifyPassengerScheduleStatus.vehicleId)
    listBuffer += rideHailModifyPassengerScheduleStatus
  }

  private def getWithInterruptId(
    interruptId: Id[Interrupt]
  ): Option[RideHailModifyPassengerScheduleStatus] = {
    interruptIdToModifyPassengerScheduleStatus.get(interruptId)
  }

  def vehicleHasMoreThanOneOngoingRequests(vehicleId: Id[Vehicle]): Boolean = {
    getWithVehicleIds(vehicleId).size > 1
  }

  def getWithVehicleIds(
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

  private def removeWithInterruptId(
    interruptId: Id[Interrupt]
  ): Option[RideHailModifyPassengerScheduleStatus] = {
    interruptIdToModifyPassengerScheduleStatus.remove(interruptId) match {
      case Some(rideHailModifyPassengerScheduleStatus) =>
        val set = vehicleIdToModifyPassengerScheduleStatus(
          rideHailModifyPassengerScheduleStatus.vehicleId
        )
        set -= rideHailModifyPassengerScheduleStatus
        Some(rideHailModifyPassengerScheduleStatus)
      case None =>
        None
    }
  }

  private def sendInterruptMessage(
    passengerScheduleStatus: RideHailModifyPassengerScheduleStatus
  ): Unit = {
    resourcesNotCheckedIn_onlyForDebugging += passengerScheduleStatus.vehicleId
    passengerScheduleStatus.status = InterruptMessageStatus.INTERRUPT_SENT
//    log.debug("sendInterruptMessage:" + passengerScheduleStatus)
    sendMessage(
      passengerScheduleStatus.rideHailAgent,
      Interrupt(passengerScheduleStatus.interruptId, passengerScheduleStatus.tick)
    )
  }

  private def printVehicleVariables(status: RideHailModifyPassengerScheduleStatus): Unit = {
//    log.debug("vehicleId status - vehicleId(" + status.vehicleId + ")")
//    log.debug("vehicleIdToModifyPassengerScheduleStatus: " + status.vehicleId + ")")
    vehicleIdToModifyPassengerScheduleStatus.get(status.vehicleId)
    val resourcesNotCheckedIn = mutable.Set[Id[Vehicle]]()
  }

  private def sendModifyPassengerScheduleMessage(
    selectedForModifyPassengerSchedule: Option[RideHailModifyPassengerScheduleStatus],
    stopDriving: Boolean
  ): Unit = {
    selectedForModifyPassengerSchedule.foreach { selected =>
      if (stopDriving) {
        sendMessage(selected.rideHailAgent, StopDriving(selected.tick))
      }
//      log.debug("sendModifyPassengerScheduleMessage: " + selectedForModifyPassengerSchedule)
      resourcesNotCheckedIn_onlyForDebugging += selected.vehicleId
      sendMessage(selected.rideHailAgent, selected.modifyPassengerSchedule)
      sendMessage(selected.rideHailAgent, Resume())
      selected.status = InterruptMessageStatus.MODIFY_PASSENGER_SCHEDULE_SENT
    }
  }

  private def sendMessage(rideHailAgent: ActorRef, message: Any): Unit = {
    rideHailAgent.tell(message, rideHailManager)
//    log.debug("sendMessages:" + message.toString)
  }

  private def isInterruptWhileDriving(
    interruptedPassengerSchedule: Option[PassengerSchedule]
  ): Boolean = {
    interruptedPassengerSchedule.isDefined
  }

  def handleInterrupt(
    interruptType: Class[_],
    interruptId: Id[Interrupt],
    interruptedPassengerSchedule: Option[PassengerSchedule],
    vehicleId: Id[Vehicle],
    tick: Double
  ): Unit = {
//    log.debug(
//      "RideHailModifyPassengerScheduleManager.handleInterrupt: " + interruptType.getSimpleName + " -> " + vehicleId + "; tick(" + tick + ");interruptedPassengerSchedule:" + interruptedPassengerSchedule
//    )
    interruptIdToModifyPassengerScheduleStatus.get(interruptId) match {
      case Some(modifyPassengerScheduleStatus) =>
        assert(vehicleId == modifyPassengerScheduleStatus.vehicleId)
        assert(tick == modifyPassengerScheduleStatus.tick)

//        log.debug(
//          "RideHailModifyPassengerScheduleManager.handleInterrupt: " + modifyPassengerScheduleStatus.toString
//        )
        var reservationModifyPassengerScheduleStatus =
          mutable.ListBuffer[RideHailModifyPassengerScheduleStatus]()

        for (modifyPassengerScheduleStatus <- getWithVehicleIds(
               modifyPassengerScheduleStatus.vehicleId
             )) {
          if (modifyPassengerScheduleStatus.interruptOrigin == InterruptOrigin.RESERVATION) {
            reservationModifyPassengerScheduleStatus += modifyPassengerScheduleStatus
          }
        }

        var selectedForModifyPassengerSchedule: Option[RideHailModifyPassengerScheduleStatus] = None
        val withVehicleIds = getWithVehicleIds(vehicleId)
        if (reservationModifyPassengerScheduleStatus.isEmpty) {

          if (withVehicleIds.isEmpty) {
            log.error("withVehicleIds.isEmpty: " + vehicleId)
            log.error("modifyPassengerScheduleStatus: " + modifyPassengerScheduleStatus)
            log.error(
              "interruptIdToModifyPassengerScheduleStatus: " + interruptIdToModifyPassengerScheduleStatus
                .get(interruptId)
            )

          }
          selectedForModifyPassengerSchedule = Some(withVehicleIds.last)
        } else if (reservationModifyPassengerScheduleStatus.size == 1) {

          if (modifyPassengerScheduleStatus.interruptOrigin == InterruptOrigin.REPOSITION) {
            // detected race condition with reservation interrupt: if message comming back is reposition message interrupt, then the interrupt confirmation for reservation message is on
            // its way - wait on that and count this reposition as completed.
            modifyPassengerScheduleAckReceivedForRepositioning(Vector()) // treat this as if ack received
            interruptIdToModifyPassengerScheduleStatus.remove(interruptId)
            vehicleIdToModifyPassengerScheduleStatus.put(
              vehicleId,
              vehicleIdToModifyPassengerScheduleStatus(vehicleId)
                .filterNot(x => x.interruptId == interruptId)
            )

            /*
            When we are overwriting a reposition with a reserve, we have to distinguish between interrupted
            while idle vs. interrupted while driving - while in the first case the overwrite just works fine
            without any additional effort, in the second case the rideHailAgent gets stuck (we are interrupted and
            overwriting reservation tries to interrupt again later, which is not defined). We "solve" this here by sending a resume
            message to the agent. This puts the rideHailAgent back to state driving, so that the reservation interrupt
            is received when agent is in state driving.
             */

            if (isInterruptWhileDriving(interruptedPassengerSchedule)) {
              sendMessage(modifyPassengerScheduleStatus.rideHailAgent, Resume())
            }

//            log.debug("removing due to overwrite by reserve:" + modifyPassengerScheduleStatus)
          } else {
            // process reservation interrupt confirmation
            val reservationStatus = reservationModifyPassengerScheduleStatus.head
            assert(
              reservationStatus.status != InterruptMessageStatus.UNDEFINED,
              "reservation message should not be undefined but at least should have sent out interrupt"
            )
            if (reservationStatus.status == InterruptMessageStatus.INTERRUPT_SENT) {
              // process reservation request
              selectedForModifyPassengerSchedule = Some(reservationStatus)
            } else
              log.error("RideHailModifyPassengerScheduleManager - unexpected interrupt message")
          }
        } else {
          log.error(
            "RideHailModifyPassengerScheduleManager - reservationModifyPassengerScheduleStatus contained more than one rideHail reservation request for same vehicle(" + vehicleId + ")"
          )
          reservationModifyPassengerScheduleStatus.foreach(
            a => log.error("reservation requests:" + a.toString)
          )
        }
        sendModifyPassengerScheduleMessage(
          selectedForModifyPassengerSchedule,
          isInterruptWhileDriving(interruptedPassengerSchedule)
        )
      case None =>
        log.error(
          "RideHailModifyPassengerScheduleManager- interruptId not found: interruptId(" + interruptId + "),interruptType(" + interruptType + "),interruptedPassengerSchedule(" + interruptedPassengerSchedule + "),vehicleId(" + vehicleId + "),tick(" + tick + ")"
        )
        //log.debug(getWithVehicleIds(vehicleId).toString())
//        printState()
        modifyPassengerScheduleAckReceivedForRepositioning(Vector())
      //DebugLib.stopSystemAndReportInconsistency()
    }
  }

  def setNumberOfRepositioningsToProcess(awaitAcks: Int): Unit = {
//    log.debug(
//      "RideHailAllocationManagerTimeout.setNumberOfRepositioningsToProcess to: " + awaitAcks
//    )
    numberOfOutStandingmodifyPassengerScheduleAckForRepositioning = awaitAcks
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
          getWithVehicleIds(x).size,
          getWithVehicleIds(x)
        )
      }
      interruptIdToModifyPassengerScheduleStatus.foreach { x =>
        log.debug("interruptId: {} -> {}", x._1, x._2)
      }
      log.debug("printState END")
    }
  }

  def startWaiveOfRepositioningRequests(tick: Double, triggerId: Long): Unit = {
//    log.debug(
//      "RepositioningTimeout(" + tick + ") - START repositioning waive - triggerId(" + triggerId + ")"
//    )
////    printState()
    assert(
      (vehicleIdToModifyPassengerScheduleStatus.toVector.unzip._2
        .filter(x => x.size != 0))
        .size == resourcesNotCheckedIn_onlyForDebugging
        .filter(x => getWithVehicleIds(x).size != 0)
        .size
    )
    assert(numberOfOutStandingmodifyPassengerScheduleAckForRepositioning <= 0)
    val timerTrigger = RideHailAllocationManagerTimeout(
      tick + rideHailAllocationManagerTimeoutInSeconds
    )
    val timerMessage = ScheduleTrigger(timerTrigger, rideHailManager)
    nextCompleteNoticeRideHailAllocationTimeout = Some(CompletionNotice(triggerId, Vector(timerMessage)))
  }

  def sendoutAckMessageToSchedulerForRideHailAllocationmanagerTimeout(): Unit = {
    log.debug(
      "sending ACK to scheduler for next repositionTimeout ({})",
      nextCompleteNoticeRideHailAllocationTimeout.get.id
    )

    val rideHailAllocationManagerTimeout = nextCompleteNoticeRideHailAllocationTimeout.get.newTriggers
      .filter(x => x.trigger.isInstanceOf[RideHailAllocationManagerTimeout])
      .head
      .trigger

    val badTriggers = nextCompleteNoticeRideHailAllocationTimeout.get.newTriggers.filter(
      x =>
        x.trigger.tick < rideHailAllocationManagerTimeout.tick - beamConfig.beam.agentsim.agents.rideHail.allocationManager.timeoutInSeconds
    )

    if (!badTriggers.isEmpty) {
      log.error("trying to schedule trigger: " + badTriggers)
      assert(false)
    }

    scheduler ! nextCompleteNoticeRideHailAllocationTimeout.get
//    printState()
  }

  var ignoreErrorPrint = true

  def modifyPassengerScheduleAckReceivedForRepositioning(
    triggersToSchedule: Seq[BeamAgentScheduler.ScheduleTrigger]
  ): Unit = {
    numberOfOutStandingmodifyPassengerScheduleAckForRepositioning -= 1
    log.debug(
      "new numberOfOutStandingmodifyPassengerScheduleAckForRepositioning=" + numberOfOutStandingmodifyPassengerScheduleAckForRepositioning
    )

    if (!triggersToSchedule.isEmpty) {
      val vehicleId: Id[Vehicle] = Id.create(
        triggersToSchedule.head.agent.path.name.replace("rideHailAgent", "rideHailVehicle"),
        classOf[Vehicle]
      )
      val vehicles = getWithVehicleIds(vehicleId)
      if (vehicles.size > 2 && ignoreErrorPrint) {
        log.error(
          s"more rideHailVehicle interruptions in process than should be possible: $vehicleId -> further errors supressed (debug later if this is still relevant)"
        )
        ignoreErrorPrint = false
      }

      if (vehicles.size > 1 && !vehicles
            .filter(x => x.interruptOrigin == InterruptOrigin.RESERVATION)
            .isEmpty) {
        // this means there is a race condition between a repositioning and reservation message and we should remove the reposition/not process it further

        // ALREADY removed in handle interruption

        //  val status=vehicles.filter(x=>x.interruptOrigin==InterruptOrigin.REPOSITION).head
        // interruptIdToModifyPassengerScheduleStatus.remove(status.interruptId)
        // vehicleIdToModifyPassengerScheduleStatus.put(vehicleId, vehicleIdToModifyPassengerScheduleStatus.get(vehicleId).get.filterNot(x => x.interruptId == status.interruptId))
        log.debug("reposition and reservation race condition detected:" + vehicleId)
        log.debug("vehicles: " + vehicles.toString())
      }
    }

    var newTriggers = triggersToSchedule.toVector
    if (nextCompleteNoticeRideHailAllocationTimeout.isDefined) {
      newTriggers = newTriggers ++ nextCompleteNoticeRideHailAllocationTimeout.get.newTriggers
      nextCompleteNoticeRideHailAllocationTimeout = Some(
        CompletionNotice(nextCompleteNoticeRideHailAllocationTimeout.get.id, newTriggers)
      )
    }

    if (numberOfOutStandingmodifyPassengerScheduleAckForRepositioning == 0) {
      sendoutAckMessageToSchedulerForRideHailAllocationmanagerTimeout()
    }
  }

  def repositionVehicle(
    passengerSchedule: PassengerSchedule,
    tick: Double,
    vehicleId: Id[Vehicle],
    rideHailAgent: ActorRef
  ): Unit = {
    log.debug("RideHailModifyPassengerScheduleManager- repositionVehicle request: " + vehicleId)
    sendInterruptMessage(
      ModifyPassengerSchedule(passengerSchedule),
      tick,
      vehicleId,
      rideHailAgent,
      InterruptOrigin.REPOSITION
    )
  }

  def reserveVehicle(
    passengerSchedule: PassengerSchedule,
    tick: Double,
    rideHailAgent: RideHailAgentLocation,
    inquiryId: Option[Int]
  ): Unit = {
    log.debug(
      "RideHailModifyPassengerScheduleManager- reserveVehicle request: " + rideHailAgent.vehicleId
    )
    sendInterruptMessage(
      ModifyPassengerSchedule(passengerSchedule, inquiryId),
      tick,
      rideHailAgent.vehicleId,
      rideHailAgent.rideHailAgent,
      InterruptOrigin.RESERVATION
    )
  }

  private def sendInterruptMessage(
    modifyPassengerSchedule: ModifyPassengerSchedule,
    tick: Double,
    vehicleId: Id[Vehicle],
    rideHailAgent: ActorRef,
    interruptOrigin: InterruptOrigin.Value
  ): Unit = {
    val rideHailAgentInterruptId =
      RideHailModifyPassengerScheduleManager.nextRideHailAgentInterruptId
    var interruptMessageStatus = InterruptMessageStatus.UNDEFINED

    val rideHailModifyPassengerScheduleStatus = RideHailModifyPassengerScheduleStatus(
      rideHailAgentInterruptId,
      vehicleId,
      modifyPassengerSchedule,
      interruptOrigin,
      tick,
      rideHailAgent,
      interruptMessageStatus
    )

    val withVehicleIdStats = getWithVehicleIds(vehicleId)
    val processInterrupt = noPendingReservations(vehicleId)
    add(rideHailModifyPassengerScheduleStatus)

    if (processInterrupt) {
      //log.debug("RideHailModifyPassengerScheduleManager- sendInterruptMessage: " + rideHailModifyPassengerScheduleStatus)
      sendInterruptMessage(rideHailModifyPassengerScheduleStatus)
    } else {
      modifyPassengerScheduleAckReceivedForRepositioning(Vector()) // treat this as if ack received
      removeWithInterruptId(rideHailAgentInterruptId)
      log.debug(
        "RideHailModifyPassengerScheduleManager- message ignored as repositioning cannot overwrite reserve: " + rideHailModifyPassengerScheduleStatus
      )
    }
  }

  def isPendingReservationEnding(
    vehicleId: Id[Vehicle],
    passengerSchedule: PassengerSchedule
  ): Boolean = {
    var result = false
    getWithVehicleIds(vehicleId)
      .filter(_.interruptOrigin == InterruptOrigin.RESERVATION)
      .headOption
      .foreach { stats =>
        result = stats.modifyPassengerSchedule.updatedPassengerSchedule == passengerSchedule
      }

    if (result) {
      val a = getWithVehicleIds(vehicleId)
        .filter(_.interruptOrigin == InterruptOrigin.RESERVATION)
        .head
        .modifyPassengerSchedule
        .updatedPassengerSchedule
      val b = passengerSchedule
      DebugLib.emptyFunctionForSettingBreakPoint()
    }

    result
  }

  def noPendingReservations(vehicleId: Id[Vehicle]): Boolean = {
    !getWithVehicleIds(vehicleId).exists(_.interruptOrigin == InterruptOrigin.RESERVATION)
  }

  def isVehicleNeitherRepositioningNorProcessingReservation(vehicleId: Id[Vehicle]): Boolean = {
    getWithVehicleIds(vehicleId).isEmpty
  }

  def checkInResource(
    vehicleId: Id[Vehicle],
    availableIn: Option[SpaceTime],
    passengerSchedule: Option[PassengerSchedule]
  ): Unit = {
    passengerSchedule match {
      case Some(passengerSchedule) =>
        var rideHailModifyPassengerScheduleStatusSet = getWithVehicleIds(vehicleId)
        var deleteItems = mutable.ListBuffer[RideHailModifyPassengerScheduleStatus]()
//        log.debug(
//          "BEFORE checkin.removeWithVehicleId({}):{}, passengerSchedule: {}",
//          rideHailModifyPassengerScheduleStatusSet.size,
//          rideHailModifyPassengerScheduleStatusSet,
//          passengerSchedule
//        )
        val listSizeAtStart = rideHailModifyPassengerScheduleStatusSet.size

        rideHailModifyPassengerScheduleStatusSet.foreach { status =>
          if (status.modifyPassengerSchedule.updatedPassengerSchedule == passengerSchedule) {
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
            val passengerSchduleLastLeg = passengerSchedule.schedule.toVector.last._1
            val endTime = beamLeg.endTime

            if (beamLeg.endTime != passengerSchduleLastLeg.endTime && status.interruptOrigin == InterruptOrigin.RESERVATION) {
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
              rideHailModifyPassengerScheduleStatusSet = getWithVehicleIds(vehicleId)
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
}

object InterruptMessageStatus extends Enumeration {
  val UNDEFINED, INTERRUPT_SENT, MODIFY_PASSENGER_SCHEDULE_SENT, EXECUTED = Value
}

object InterruptOrigin extends Enumeration {
  val RESERVATION, REPOSITION = Value
}

case class RideHailModifyPassengerScheduleStatus(
  interruptId: Id[Interrupt],
  vehicleId: Id[Vehicle],
  modifyPassengerSchedule: ModifyPassengerSchedule,
  interruptOrigin: InterruptOrigin.Value,
  tick: Double,
  rideHailAgent: ActorRef,
  var status: InterruptMessageStatus.Value = InterruptMessageStatus.UNDEFINED
)

case class RepositionVehicleRequest(
  passengerSchedule: PassengerSchedule,
  tick: Double,
  vehicleId: Id[Vehicle],
  rideHailAgent: ActorRef
)

case object ReduceAwaitingRepositioningAckMessagesByOne

object RideHailModifyPassengerScheduleManager {

  def nextRideHailAgentInterruptId: Id[Interrupt] = {
    Id.create(UUIDGen.createTime(UUIDGen.newTime()).toString, classOf[Interrupt])
  }
}
