package beam.agentsim.agents.rideHail

import akka.actor.ActorRef
import akka.event.LoggingAdapter
import beam.agentsim.agents.modalBehaviors.DrivesVehicle.StopDriving
import beam.agentsim.agents.rideHail.RideHailingAgent.{Interrupt, ModifyPassengerSchedule, Resume}
import beam.agentsim.agents.vehicles.PassengerSchedule
import com.eaio.uuid.UUIDGen
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

import scala.collection.{concurrent, mutable}

class RideHailModifyPassengerScheduleManager(val log: LoggingAdapter) {

  val modifyPassengerScheduleStatus = mutable.Map[Id[Interrupt], RideHailModifyPassengerScheduleStatus]()
  val vehicleInterruptIds = mutable.Map[Id[Vehicle], mutable.Set[RideHailModifyPassengerScheduleStatus]]()

  def add(rideHailModifyPassengerScheduleStatus: RideHailModifyPassengerScheduleStatus): Unit = {
    modifyPassengerScheduleStatus.put(rideHailModifyPassengerScheduleStatus.interruptId, rideHailModifyPassengerScheduleStatus)
    addToVehicleInterruptIds(rideHailModifyPassengerScheduleStatus)
  }

  private def addToVehicleInterruptIds(rideHailModifyPassengerScheduleStatus: RideHailModifyPassengerScheduleStatus): Unit = {
    if (!vehicleInterruptIds.contains(rideHailModifyPassengerScheduleStatus.vehicleId)) {
      vehicleInterruptIds.put(rideHailModifyPassengerScheduleStatus.vehicleId, mutable.Set[RideHailModifyPassengerScheduleStatus]())
    }
    var set = vehicleInterruptIds.get(rideHailModifyPassengerScheduleStatus.vehicleId).get
    set.add(rideHailModifyPassengerScheduleStatus)
  }

  def getWithInterruptId(interruptId: Id[Interrupt]): Option[RideHailModifyPassengerScheduleStatus] = {
    modifyPassengerScheduleStatus.get(interruptId)
  }

  def getWithVehicleIds(vehicleId: Id[Vehicle]): Set[RideHailModifyPassengerScheduleStatus] = {
    collection.immutable.Set(vehicleInterruptIds.get(vehicleId).get.toVector: _*)
  }

  def remove(interruptId: Id[Interrupt]): Option[RideHailModifyPassengerScheduleStatus] = {
    modifyPassengerScheduleStatus.remove(interruptId) match {
      case Some(rideHailModifyPassengerScheduleStatus) =>
        val set = vehicleInterruptIds.get(rideHailModifyPassengerScheduleStatus.vehicleId).get
        set.remove(rideHailModifyPassengerScheduleStatus)
        Some(rideHailModifyPassengerScheduleStatus)
      case None =>
        None
    }
  }


  def handleInterrupt(interruptType: String, interruptId: Id[Interrupt], interruptedPassengerSchedule: Option[PassengerSchedule], vehicleId: Id[Vehicle], tick: Double, rideHailAgent: ActorRef): Unit = {
    log.debug(interruptType + " - vehicle: " + vehicleId)


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
}

object InterruptMessageStatus extends Enumeration {
  val UNDEFINED, INTERRUPT_SENT, MODIFY_PASSENGER_SCHEDULE_SENT, EXECUTED = Value
}

object InterruptOrigin extends Enumeration {
  val RESERVATION, REPOSITION = Value
}

class RideHailModifyPassengerScheduleStatus(val interruptId: Id[Interrupt], val vehicleId: Id[Vehicle], val passengerSchedule: PassengerSchedule, val interruptOrigin: InterruptOrigin.Value, var status: InterruptMessageStatus.Value = InterruptMessageStatus.INTERRUPT_SENT) {}


object RideHailModifyPassengerScheduleManager {


  def nextRideHailAgentInterruptId: Id[Interrupt] = {
    Id.create(UUIDGen.createTime(UUIDGen.newTime()).toString, classOf[Interrupt])
  }
}
