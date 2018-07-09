package beam.agentsim.agents.rideHail

import akka.actor.ActorRef
import akka.event.LoggingAdapter
import beam.agentsim.agents.modalBehaviors.DrivesVehicle.StopDriving
import beam.agentsim.agents.rideHail.RideHailAgent.{Interrupt, InterruptedAt, ModifyPassengerSchedule, Resume}
import beam.agentsim.agents.rideHail.RideHailManager.{RideHailAllocationManagerTimeout, RideHailInquiry}
import beam.agentsim.agents.vehicles.PassengerSchedule
import beam.agentsim.events.SpaceTime
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.utils.DebugLib
import com.eaio.uuid.UUIDGen
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

import scala.collection.mutable.ListBuffer
import scala.collection.{concurrent, mutable}

class RideHailModifyPassengerScheduleManager(val log: LoggingAdapter, val rideHailManager: ActorRef, val rideHailAllocationManagerTimeoutInSeconds:Double, val scheduler: ActorRef) {

  private val interruptIdToModifyPassengerScheduleStatus = mutable.Map[Id[Interrupt], RideHailModifyPassengerScheduleStatus]()
  private val vehicleIdToModifyPassengerScheduleStatus = mutable.Map[Id[Vehicle], mutable.ListBuffer[RideHailModifyPassengerScheduleStatus]]()
  private val resourcesNotCheckedIn = mutable.Set[Id[Vehicle]]()

  private def add(rideHailModifyPassengerScheduleStatus: RideHailModifyPassengerScheduleStatus): Unit = {
    interruptIdToModifyPassengerScheduleStatus.put(rideHailModifyPassengerScheduleStatus.interruptId, rideHailModifyPassengerScheduleStatus)
    addToVehicleInterruptIds(rideHailModifyPassengerScheduleStatus)
  }

  private def addToVehicleInterruptIds(rideHailModifyPassengerScheduleStatus: RideHailModifyPassengerScheduleStatus): Unit = {
    var listBuffer = getWithVehicleIds(rideHailModifyPassengerScheduleStatus.vehicleId)
    listBuffer += rideHailModifyPassengerScheduleStatus
  }

  private def getWithInterruptId(interruptId: Id[Interrupt]): Option[RideHailModifyPassengerScheduleStatus] = {
    interruptIdToModifyPassengerScheduleStatus.get(interruptId)
  }

  private def getWithVehicleIds(vehicleId: Id[Vehicle]):  mutable.ListBuffer[RideHailModifyPassengerScheduleStatus] = {
    if (!vehicleIdToModifyPassengerScheduleStatus.contains(vehicleId)) {
      vehicleIdToModifyPassengerScheduleStatus.put(vehicleId, mutable.ListBuffer[RideHailModifyPassengerScheduleStatus]())
    }

    vehicleIdToModifyPassengerScheduleStatus(vehicleId)
  }

  private def removeWithInterruptId(interruptId: Id[Interrupt]): Option[RideHailModifyPassengerScheduleStatus] = {
    interruptIdToModifyPassengerScheduleStatus.remove(interruptId) match {
      case Some(rideHailModifyPassengerScheduleStatus) =>
        val set = vehicleIdToModifyPassengerScheduleStatus(rideHailModifyPassengerScheduleStatus.vehicleId)
        set -= rideHailModifyPassengerScheduleStatus
        Some(rideHailModifyPassengerScheduleStatus)
      case None =>
        None
    }
  }


  private def sendInterruptMessage( passengerScheduleStatus: RideHailModifyPassengerScheduleStatus): Unit ={
    if (!interruptIdToModifyPassengerScheduleStatus.contains(passengerScheduleStatus.interruptId)){
      DebugLib.emptyFunctionForSettingBreakPoint()
    }

    resourcesNotCheckedIn += passengerScheduleStatus.vehicleId

    passengerScheduleStatus.status=InterruptMessageStatus.INTERRUPT_SENT
    log.debug("sendInterruptMessage:" + passengerScheduleStatus)
    sendMessage(passengerScheduleStatus.rideHailAgent, Interrupt(passengerScheduleStatus.interruptId, passengerScheduleStatus.tick))
  }

  private def printVehicleVariables(status: RideHailModifyPassengerScheduleStatus): Unit ={
    log.debug("vehicleId status - vehicleId(" +  status.vehicleId + ")")
    log.debug("vehicleIdToModifyPassengerScheduleStatus: " +  status.vehicleId + ")")
    vehicleIdToModifyPassengerScheduleStatus.get(status.vehicleId)

    val resourcesNotCheckedIn=mutable.Set[Id[Vehicle]]()
  }

  private def sendModifyPassengerScheduleMessage(selectedForModifyPassengerSchedule: Option[RideHailModifyPassengerScheduleStatus],  stopDriving:Boolean): Unit ={
    selectedForModifyPassengerSchedule.foreach{selected =>

      log.debug("sendModifyPassengerScheduleMessage: "+ selectedForModifyPassengerSchedule)


      if (stopDriving){
        sendMessage(selected.rideHailAgent, StopDriving())
      }

      resourcesNotCheckedIn += selected.vehicleId
      sendMessage(selected.rideHailAgent,selected.modifyPassengerSchedule)
      sendMessage(selected.rideHailAgent,Resume())
      selected.status=InterruptMessageStatus.MODIFY_PASSENGER_SCHEDULE_SENT
    }
  }

  private def sendMessage(rideHailAgent: ActorRef, message: Any): Unit ={
      rideHailAgent.tell(message,rideHailManager)
      log.debug("sendMessages:" + message.toString)
  }




  def handleInterrupt(interruptType: Class[_], interruptId: Id[Interrupt], interruptedPassengerSchedule: Option[PassengerSchedule], vehicleId: Id[Vehicle], tick: Double): Unit = {
    log.debug("RideHailModifyPassengerScheduleManager.handleInterrupt: "  + interruptType.getSimpleName + " -> " + vehicleId)
    interruptIdToModifyPassengerScheduleStatus.get(interruptId) match {
      case Some(modifyPassengerScheduleStatus) =>
        assert(vehicleId==modifyPassengerScheduleStatus.vehicleId)
        assert(tick==modifyPassengerScheduleStatus.tick)

        log.debug("RideHailModifyPassengerScheduleManager.handleInterrupt: " + modifyPassengerScheduleStatus.toString)

        var reservationModifyPassengerScheduleStatus=mutable.ListBuffer[RideHailModifyPassengerScheduleStatus]()

       //  getWithVehicleIds(modifyPassengerScheduleStatus.vehicleId).filter(_.interruptOrigin==InterruptOrigin.RESERVATION) TODO: replace below block with this line

        for (modifyPassengerScheduleStatus <-getWithVehicleIds(modifyPassengerScheduleStatus.vehicleId)){
          if (modifyPassengerScheduleStatus.interruptOrigin==InterruptOrigin.RESERVATION){
            reservationModifyPassengerScheduleStatus +=modifyPassengerScheduleStatus
          }
        }

        var selectedForModifyPassengerSchedule:Option[RideHailModifyPassengerScheduleStatus]=None
        var withVehicleIds=getWithVehicleIds(vehicleId)
        if (reservationModifyPassengerScheduleStatus.isEmpty){

          if (withVehicleIds.isEmpty){
            log.error("withVehicleIds.isEmpty: " + vehicleId)
            log.error("modifyPassengerScheduleStatus: " + modifyPassengerScheduleStatus)
            log.error("interruptIdToModifyPassengerScheduleStatus: " + interruptIdToModifyPassengerScheduleStatus.get(interruptId))

          }

          // find out which repositioning to process
          //log.debug("RideHailModifyPassengerScheduleManager - getWithVehicleIds.size: " + withVehicleIds.size + ",vehicleId(" + vehicleId + ")")
          selectedForModifyPassengerSchedule=Some(withVehicleIds.last)
          DebugLib.emptyFunctionForSettingBreakPoint()
          // TODO: allow soon most recent one
        } else if (reservationModifyPassengerScheduleStatus.size==1){


          if (modifyPassengerScheduleStatus.interruptOrigin==InterruptOrigin.REPOSITION){
            // detected race condition with reservation interrupt: if message comming back is reposition message interrupt, then the interrupt confirmation for reservation message is on
            // its way - wait on that and count this reposition as completed.

            modifyPassengerScheduleAckReceivedForRepositioning(Vector()) // treat this as if ack received

          } else {
            // process reservation interrupt confirmation
            val reservationStatus=reservationModifyPassengerScheduleStatus.head


            assert(reservationStatus.status!= InterruptMessageStatus.UNDEFINED,"reservation message should not be undefined but at least should have sent out interrupt")

            if (reservationStatus.status== InterruptMessageStatus.INTERRUPT_SENT) {
              // process reservation request
              selectedForModifyPassengerSchedule=Some(reservationStatus)

            } else (
              log.error("RideHailModifyPassengerScheduleManager - unexpected interrupt message")
              )
          }

        } else {
          log.error("RideHailModifyPassengerScheduleManager - reservationModifyPassengerScheduleStatus contained more than one rideHail reservation request for same vehicle(" + vehicleId + ")")
          reservationModifyPassengerScheduleStatus.foreach(a => log.error("reservation requests:"+ a.toString))
        }



        sendModifyPassengerScheduleMessage(selectedForModifyPassengerSchedule,interruptedPassengerSchedule.isDefined)
      case None =>
        log.error("RideHailModifyPassengerScheduleManager- interruptId not found: interruptId(" + interruptId + "),interruptType(" + interruptType+  "),interruptedPassengerSchedule(" + interruptedPassengerSchedule+ "),vehicleId(" + vehicleId+ "),tick(" + tick+")")
    }

  }


  var nextCompleteNoticeRideHailAllocationTimeout: CompletionNotice = _
  var numberOfOutStandingmodifyPassengerScheduleAckForRepositioning:Int = 0
  //val buffer mutable.ListBuffer


  def setNumberOfRepositioningsToProcess(awaitAcks:Int): Unit ={
    log.debug("RideHailAllocationManagerTimeout.setNumberOfRepositioningsToProcess to: " + awaitAcks)
    numberOfOutStandingmodifyPassengerScheduleAckForRepositioning=awaitAcks
  }

  def printState(): Unit ={
    vehicleIdToModifyPassengerScheduleStatus.foreach(x => log.debug(x._1 + " -> " +  x._2))
    resourcesNotCheckedIn.foreach(x=>log.debug("resource not checked in:" + x.toString + "-> getWithVehicleIds(" + getWithVehicleIds(x).size + "):" + getWithVehicleIds(x)))
    interruptIdToModifyPassengerScheduleStatus.foreach(x=>log.debug(x._1 + " -> " +  x._2))
  }

  def startWaiveOfRepositioningRequests(tick: Double, triggerId: Long): Unit ={
    log.debug("RepositioningTimeout("+tick +") - START repositioning waive - triggerId(" +triggerId+")"  )

    printState()


    assert((vehicleIdToModifyPassengerScheduleStatus.toVector.unzip._2.filter(x=>x.size!=0)).size==resourcesNotCheckedIn.filter(x=>getWithVehicleIds(x).size!=0).size)

    assert(numberOfOutStandingmodifyPassengerScheduleAckForRepositioning==0)

    val timerTrigger = RideHailAllocationManagerTimeout(tick + rideHailAllocationManagerTimeoutInSeconds)
    val timerMessage = ScheduleTrigger(timerTrigger, rideHailManager)
    nextCompleteNoticeRideHailAllocationTimeout = CompletionNotice(triggerId, Vector(timerMessage))
  }

 // def endWaiveOfRepositioningRequests={
 //   log.debug(end)
  //}




  def sendoutAckMessageToSchedulerForRideHailAllocationmanagerTimeout(): Unit = {
   // DebugLib.stopSystemAndReportInconsistency("probably not implemented correctly yet:")

    if (nextCompleteNoticeRideHailAllocationTimeout.id==33519){
      DebugLib.emptyFunctionForSettingBreakPoint()
    }

    log.debug("sending ACK to scheduler for next repositionTimeout (" + nextCompleteNoticeRideHailAllocationTimeout.id + ")")
    scheduler ! nextCompleteNoticeRideHailAllocationTimeout

    printState()


    }

  def modifyPassengerScheduleAckReceivedForRepositioning(triggersToSchedule: Seq[BeamAgentScheduler.ScheduleTrigger]): Unit ={
    numberOfOutStandingmodifyPassengerScheduleAckForRepositioning-=1

    log.debug("new numberOfOutStandingmodifyPassengerScheduleAckForRepositioning="+numberOfOutStandingmodifyPassengerScheduleAckForRepositioning)

    val newTriggers = triggersToSchedule ++ nextCompleteNoticeRideHailAllocationTimeout.newTriggers
    nextCompleteNoticeRideHailAllocationTimeout=CompletionNotice(nextCompleteNoticeRideHailAllocationTimeout.id, newTriggers)

    if (numberOfOutStandingmodifyPassengerScheduleAckForRepositioning==0){
      sendoutAckMessageToSchedulerForRideHailAllocationmanagerTimeout()
    }
  }

  def repositionVehicle(passengerSchedule:PassengerSchedule,tick:Double,vehicleId:Id[Vehicle],rideHailAgent: ActorRef):Unit={
    log.debug("RideHailModifyPassengerScheduleManager- repositionVehicle request: " + vehicleId)
    //numberOfOutStandingmodifyPassengerScheduleAckForRepositioning+=1
    sendInterruptMessage(ModifyPassengerSchedule(passengerSchedule),tick,vehicleId,rideHailAgent,InterruptOrigin.REPOSITION)
  }

  def reserveVehicle(passengerSchedule:PassengerSchedule,tick:Double,vehicleId:Id[Vehicle],rideHailAgent: ActorRef,inquiryId: Option[Id[RideHailInquiry]]):Unit={
    log.debug("RideHailModifyPassengerScheduleManager- reserveVehicle request: " + vehicleId)
    sendInterruptMessage(ModifyPassengerSchedule(passengerSchedule,inquiryId),tick,vehicleId,rideHailAgent,InterruptOrigin.RESERVATION)
  }

   private def sendInterruptMessage(modifyPassengerSchedule:ModifyPassengerSchedule,tick:Double,vehicleId:Id[Vehicle],rideHailAgent: ActorRef, interruptOrigin: InterruptOrigin.Value):Unit={
     val rideHailAgentInterruptId = RideHailModifyPassengerScheduleManager.nextRideHailAgentInterruptId
     var interruptMessageStatus=InterruptMessageStatus.UNDEFINED

     val rideHailModifyPassengerScheduleStatus = new RideHailModifyPassengerScheduleStatus(rideHailAgentInterruptId, vehicleId, modifyPassengerSchedule, interruptOrigin, tick, rideHailAgent, interruptMessageStatus)

     val withVehicleIdStats=getWithVehicleIds(vehicleId)
     val processInterrupt=containsPendingReservations(vehicleId)
     add(rideHailModifyPassengerScheduleStatus)


     if (!getWithVehicleIds(vehicleId).filter(_.status!=InterruptMessageStatus.UNDEFINED).isEmpty && interruptOrigin==InterruptOrigin.RESERVATION){
       DebugLib.emptyFunctionForSettingBreakPoint()
     }



     if (processInterrupt){
       //log.debug("RideHailModifyPassengerScheduleManager- sendInterruptMessage: " + rideHailModifyPassengerScheduleStatus)
       sendInterruptMessage(rideHailModifyPassengerScheduleStatus)
     } else {
       modifyPassengerScheduleAckReceivedForRepositioning(Vector()) // treat this as if ack received
        removeWithInterruptId(rideHailAgentInterruptId)
       log.debug("RideHailModifyPassengerScheduleManager- message ignored as repositioning cannot overwrite reserve: " + rideHailModifyPassengerScheduleStatus)
     }



   }

  def containsPendingReservations(vehicleId:Id[Vehicle]): Boolean ={
    getWithVehicleIds(vehicleId).filter(_.interruptOrigin==InterruptOrigin.RESERVATION).isEmpty
  }

  def checkInResource(vehicleId:Id[Vehicle], availableIn: Option[SpaceTime]): Unit ={
    var rideHailModifyPassengerScheduleStatusSet=getWithVehicleIds(vehicleId)
    var deleteItems=mutable.ListBuffer[RideHailModifyPassengerScheduleStatus]();
    log.debug("BEFORE checkin.removeWithVehicleId("+ rideHailModifyPassengerScheduleStatusSet.size  +"):" + rideHailModifyPassengerScheduleStatusSet)
    rideHailModifyPassengerScheduleStatusSet.foreach{
      rideHailModifyPassengerScheduleStatus =>

        //if (rideHailModifyPassengerScheduleStatus.tick<=availableIn.get.time){
          if (rideHailModifyPassengerScheduleStatus.status==InterruptMessageStatus.MODIFY_PASSENGER_SCHEDULE_SENT){
           // interruptIdToModifyPassengerScheduleStatus.remove(rideHailModifyPassengerScheduleStatus.interruptId)
            deleteItems+=rideHailModifyPassengerScheduleStatus
          }
        //}
    }

    if (deleteItems.size>1){
      // this means that multiple MODIFY_PASSENGER_SCHEDULE_SENT outstanding and we need to keep them in order, as otherwise
      // a
      deleteItems= deleteItems.splitAt(1)._1

    }

    if (availableIn.get.time>0){
      interruptIdToModifyPassengerScheduleStatus.remove(deleteItems.head.interruptId)
    }

    vehicleIdToModifyPassengerScheduleStatus.put(vehicleId,rideHailModifyPassengerScheduleStatusSet diff deleteItems)

    rideHailModifyPassengerScheduleStatusSet=getWithVehicleIds(vehicleId)


    if (rideHailModifyPassengerScheduleStatusSet.size==0){
      resourcesNotCheckedIn.remove(vehicleId)
    }
    // only something new, if all undefined (no pending query)
    if (!rideHailModifyPassengerScheduleStatusSet.isEmpty && rideHailModifyPassengerScheduleStatusSet.filter(_.status==InterruptMessageStatus.UNDEFINED).size==rideHailModifyPassengerScheduleStatusSet.size){
      sendInterruptMessage(rideHailModifyPassengerScheduleStatusSet.head)
    }

    log.debug("AFTER checkin.removeWithVehicleId("+ rideHailModifyPassengerScheduleStatusSet.size  +"):" + getWithVehicleIds(vehicleId))
  }





}

object InterruptMessageStatus extends Enumeration {
  val UNDEFINED, INTERRUPT_SENT, MODIFY_PASSENGER_SCHEDULE_SENT, EXECUTED = Value
}

object InterruptOrigin extends Enumeration {
  val RESERVATION, REPOSITION = Value
}

case class RideHailModifyPassengerScheduleStatus(val interruptId: Id[Interrupt], val vehicleId: Id[Vehicle], val modifyPassengerSchedule: ModifyPassengerSchedule, val interruptOrigin: InterruptOrigin.Value, val tick:Double, val rideHailAgent:ActorRef, var status: InterruptMessageStatus.Value = InterruptMessageStatus.UNDEFINED)


case class RepositionVehicleRequest(passengerSchedule:PassengerSchedule,tick:Double,vehicleId:Id[Vehicle],rideHailAgent: ActorRef)


case object ReduceAwaitingRepositioningAckMessagesByOne

object RideHailModifyPassengerScheduleManager {
  def nextRideHailAgentInterruptId: Id[Interrupt] = {
    Id.create(UUIDGen.createTime(UUIDGen.newTime()).toString, classOf[Interrupt])
  }
}
