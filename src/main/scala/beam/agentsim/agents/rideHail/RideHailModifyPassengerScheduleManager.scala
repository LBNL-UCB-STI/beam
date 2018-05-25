package beam.agentsim.agents.rideHail

import akka.actor.ActorRef
import akka.event.LoggingAdapter
import beam.agentsim.agents.modalBehaviors.DrivesVehicle.StopDriving
import beam.agentsim.agents.rideHail.RideHailingAgent.{Interrupt, InterruptedAt, ModifyPassengerSchedule, Resume}
import beam.agentsim.agents.rideHail.RideHailingManager.RideHailingInquiry
import beam.agentsim.agents.vehicles.PassengerSchedule
import beam.agentsim.events.SpaceTime
import com.eaio.uuid.UUIDGen
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

import scala.collection.mutable.ListBuffer
import scala.collection.{concurrent, mutable}

class RideHailModifyPassengerScheduleManager(val log: LoggingAdapter) {

  val interruptIdToModifyPassengerScheduleStatus = mutable.Map[Id[Interrupt], RideHailModifyPassengerScheduleStatus]()
  val vehicleIdToModifyPassengerScheduleStatus = mutable.Map[Id[Vehicle], mutable.ListBuffer[RideHailModifyPassengerScheduleStatus]]()

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

    vehicleIdToModifyPassengerScheduleStatus.get(vehicleId).get
  }

  private def removeWithInterruptId(interruptId: Id[Interrupt]): Option[RideHailModifyPassengerScheduleStatus] = {
    interruptIdToModifyPassengerScheduleStatus.remove(interruptId) match {
      case Some(rideHailModifyPassengerScheduleStatus) =>
        val set = vehicleIdToModifyPassengerScheduleStatus.get(rideHailModifyPassengerScheduleStatus.vehicleId).get
        set -= rideHailModifyPassengerScheduleStatus
        Some(rideHailModifyPassengerScheduleStatus)
      case None =>
        None
    }
  }

  private def removeWithVehicleId(vehicleId:Id[Vehicle], time:Long): Unit ={
    getWithVehicleIds(vehicleId).foreach{
      rideHailModifyPassengerScheduleStatus =>

        if (rideHailModifyPassengerScheduleStatus.tick<time){
          interruptIdToModifyPassengerScheduleStatus.remove(rideHailModifyPassengerScheduleStatus.interruptId)
        }

    }

    vehicleIdToModifyPassengerScheduleStatus.put(vehicleId,getWithVehicleIds(vehicleId).filter(rideHailModifyPassengerScheduleStatus => rideHailModifyPassengerScheduleStatus.tick<time))
  }




  def handleInterrupt(interruptType: Class[_], interruptId: Id[Interrupt], interruptedPassengerSchedule: Option[PassengerSchedule], vehicleId: Id[Vehicle], tick: Double): mutable.ListBuffer[_] = {
    val messages=mutable.ListBuffer[Any]()
    interruptIdToModifyPassengerScheduleStatus.get(interruptId) match {
      case Some(modifyPassengerScheduleStatus) =>
        assert(vehicleId==modifyPassengerScheduleStatus.vehicleId)
        assert(tick==modifyPassengerScheduleStatus.tick)

        log.debug("RideHailModifyPassengerScheduleManager.handleInterrupt: " + modifyPassengerScheduleStatus.toString)

        var reservationModifyPassengerScheduleStatus=mutable.ListBuffer[RideHailModifyPassengerScheduleStatus]()
        for (modifyPassengerScheduleStatus <-getWithVehicleIds(modifyPassengerScheduleStatus.vehicleId)){
          if (modifyPassengerScheduleStatus.interruptOrigin==InterruptOrigin.RESERVATION){
            reservationModifyPassengerScheduleStatus +=modifyPassengerScheduleStatus
          }
        }

        var selectedForModifyPassengerSchedule:Option[RideHailModifyPassengerScheduleStatus]=None
        if (reservationModifyPassengerScheduleStatus.isEmpty){
          // find out which repositioning to process
          log.debug("RideHailModifyPassengerScheduleManager - getWithVehicleIds.size: " + getWithVehicleIds(vehicleId).size + ",vehicleId(" + vehicleId + ")")
          selectedForModifyPassengerSchedule=Some(getWithVehicleIds(modifyPassengerScheduleStatus.vehicleId).last)
          // TODO: allow soon most recent one
        } else if (reservationModifyPassengerScheduleStatus.size==1){
          val reservationStatus=reservationModifyPassengerScheduleStatus.head
          if (reservationStatus.status== InterruptMessageStatus.UNDEFINED ||  reservationStatus.status== InterruptMessageStatus.INTERRUPT_SENT) {
            // process reservation request
            selectedForModifyPassengerSchedule=Some(reservationStatus)

          } else (
            log.error("RideHailModifyPassengerScheduleManager - unexpected interrupt message")
          )
        } else {
          log.error("RideHailModifyPassengerScheduleManager - reservationModifyPassengerScheduleStatus contained more than one rideHail reservation request for same vehicle(" + vehicleId + ")")
          reservationModifyPassengerScheduleStatus.foreach(a => log.error("reservation requests:"+ a.toString))
        }

        selectedForModifyPassengerSchedule.foreach{selected =>
          interruptedPassengerSchedule.foreach(_ => messages += StopDriving())
          messages += selected.modifyPassengerSchedule
          messages += Resume()
        }

        messages
      case None =>
        log.error("RideHailModifyPassengerScheduleManager- interruptId not found: interruptId(" + interruptId + "),interruptType(" + interruptType+  "),interruptedPassengerSchedule(" + interruptedPassengerSchedule+ "),vehicleId(" + vehicleId+ "),tick(" + tick+")")

        messages
    }

    /*
    val rideHailAgent =getRideHailAgent(vehicleId)
    if (repositioningPassengerSchedule.contains(vehicleId)){
      val (interruptIdReposition, passengerSchedule)=repositioningPassengerSchedule.get(vehicleId).get
      if (reservationPassengerSchedule.contains(vehicleId)){
        val (interruptIdReservation, modifyPassengerSchedule)=reservationPassengerSchedule.get(vehicleId).get
        interruptedPassengerSchedule.foreach(interruptedPassengerSchedule => updateIdleVehicleLocation(vehicleId,interruptedPassengerSchedule.schedule.head._1,tick))
        log.debug(interruptType + " - ignoring reposition: " + vehicleId)
      } else {
        interruptedPassengerSchedule.foreach(_ => rideHailAgent ! StopDriving())
        rideHailAgent ! ModifyPassengerSchedule(passengerSchedule.get)
        rideHailAgent ! Resume()
        log.debug(interruptType + " - reposition: " + vehicleId)
      }
    }

    if (reservationPassengerSchedule.contains(vehicleId)) {
      val (interruptIdReservation, modifyPassengerSchedule) = reservationPassengerSchedule.get(vehicleId).get
      if (interruptId == interruptIdReservation) {
        val (interruptIdReservation, modifyPassengerSchedule) = reservationPassengerSchedule.remove(vehicleId).get
        interruptedPassengerSchedule.foreach(_ => rideHailAgent ! StopDriving())
        rideHailAgent ! modifyPassengerSchedule
        rideHailAgent ! Resume()
        log.debug(interruptType + " - reservation: " + vehicleId)
      } else {
        log.error(interruptType + " - reservation: " + vehicleId + " interruptId doesn't match (interruptId,interruptIdReservation):" + interruptId + "," + interruptIdReservation)
      }
    }
  }
  */

  }

  def repositionVehicle(passengerSchedule:PassengerSchedule,tick:Double,vehicleId:Id[Vehicle],rideHailAgent: ActorRef):ListBuffer[Interrupt]={
    log.debug("RideHailModifyPassengerScheduleManager- repositionVehicle request: " + vehicleId)
    sendInterruptMessage(ModifyPassengerSchedule(passengerSchedule),tick,vehicleId,rideHailAgent,InterruptOrigin.REPOSITION)
  }

  def reserveVehicle(passengerSchedule:PassengerSchedule,tick:Double,vehicleId:Id[Vehicle],rideHailAgent: ActorRef,inquiryId: Option[Id[RideHailingInquiry]]):ListBuffer[Interrupt]={
    log.debug("RideHailModifyPassengerScheduleManager- reserveVehicle request: " + vehicleId)
    sendInterruptMessage(ModifyPassengerSchedule(passengerSchedule,inquiryId),tick,vehicleId,rideHailAgent,InterruptOrigin.RESERVATION)
  }

   private def sendInterruptMessage(modifyPassengerSchedule:ModifyPassengerSchedule,tick:Double,vehicleId:Id[Vehicle],rideHailAgent: ActorRef, interruptOrigin: InterruptOrigin.Value):ListBuffer[Interrupt]={
     val rideHailAgentInterruptId = RideHailModifyPassengerScheduleManager.nextRideHailAgentInterruptId
     var interruptMessageStatus=InterruptMessageStatus.UNDEFINED
      if (getWithVehicleIds(vehicleId).isEmpty){
        interruptMessageStatus=InterruptMessageStatus.INTERRUPT_SENT
      }
     val rideHailModifyPassengerScheduleStatus = new RideHailModifyPassengerScheduleStatus(rideHailAgentInterruptId, vehicleId, modifyPassengerSchedule, interruptOrigin, tick, rideHailAgent, interruptMessageStatus)
     add(rideHailModifyPassengerScheduleStatus)
     log.debug("RideHailModifyPassengerScheduleManager- sendInterruptMessage: " + rideHailModifyPassengerScheduleStatus)
     ListBuffer(Interrupt(rideHailAgentInterruptId, tick))
   }

  def checkInResource(vehicleId:Id[Vehicle], availableIn: Option[SpaceTime]): Unit ={
    removeWithVehicleId(vehicleId,availableIn.get.time)
  }

}

object InterruptMessageStatus extends Enumeration {
  val UNDEFINED, INTERRUPT_SENT, MODIFY_PASSENGER_SCHEDULE_SENT, EXECUTED = Value
}

object InterruptOrigin extends Enumeration {
  val RESERVATION, REPOSITION = Value
}

case class RideHailModifyPassengerScheduleStatus(val interruptId: Id[Interrupt], val vehicleId: Id[Vehicle], val modifyPassengerSchedule: ModifyPassengerSchedule, val interruptOrigin: InterruptOrigin.Value, val tick:Double, val rideHailAgent:ActorRef, var status: InterruptMessageStatus.Value = InterruptMessageStatus.UNDEFINED)


object RideHailModifyPassengerScheduleManager {
  def nextRideHailAgentInterruptId: Id[Interrupt] = {
    Id.create(UUIDGen.createTime(UUIDGen.newTime()).toString, classOf[Interrupt])
  }
}
