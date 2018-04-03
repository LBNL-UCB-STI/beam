package beam.agentsim.agents.modalBehaviors

import akka.actor.FSM
import akka.actor.FSM.Failure
import beam.agentsim.Resource.NotifyResourceIdle
import beam.agentsim.agents.BeamAgent
import beam.agentsim.agents.BeamAgent.{AnyState, BeamAgentData}
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.TriggerUtils._
import beam.agentsim.agents.modalBehaviors.DrivesVehicle._
import beam.agentsim.agents.vehicles.AccessErrorCodes.{DriverHasEmptyPassengerScheduleError, VehicleFullError, VehicleGoneError}
import beam.agentsim.agents.vehicles.VehicleProtocol._
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.{PathTraversalEvent, SpaceTime}
import beam.agentsim.scheduler.{Trigger, TriggerWithId}
import beam.router.RoutingModel
import beam.router.RoutingModel.BeamLeg
import beam.sim.HasServices
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.{PersonEntersVehicleEvent, PersonLeavesVehicleEvent, VehicleEntersTrafficEvent, VehicleLeavesTrafficEvent}
import org.matsim.api.core.v01.population.Person
import org.matsim.vehicles.Vehicle

import scala.collection.immutable.HashSet

/**
  * @author dserdiuk on 7/29/17.
  */
object DrivesVehicle {

  case class StartLegTrigger(tick: Double, beamLeg: BeamLeg) extends Trigger

  case class EndLegTrigger(tick: Double) extends Trigger

  case class NotifyLegEndTrigger(tick: Double, beamLeg: BeamLeg) extends Trigger

  case class NotifyLegStartTrigger(tick: Double, beamLeg: BeamLeg) extends Trigger

  case class AddFuel(fuelInJoules: Double)

  case object GetBeamVehicleFuelLevel
  case class GetBeamVehicleFuelLevelResult(fuelLevel:Double, lastVisited: SpaceTime)


}

trait DrivesVehicle[T <: BeamAgentData] extends BeamAgent[T] with HasServices {

  protected val transportNetwork: TransportNetwork

  protected var passengerSchedule: PassengerSchedule = PassengerSchedule()
  var lastVisited:  SpaceTime = SpaceTime.zero
  protected var _currentLeg: Option[BeamLeg] = None
  //TODO: send some message to set _currentVehicle
  protected var _currentVehicleUnderControl: Option[BeamVehicle] = None
  protected var _awaitingBoardConfirmation: Set[Id[Vehicle]] = HashSet[Id[Vehicle]]()
  protected var _awaitingAlightConfirmation: Set[Id[Vehicle]] = HashSet[Id[Vehicle]]()

  def passengerScheduleEmpty(tick: Double, triggerId: Long): State

  chainedWhen(Moving) {
    case Event(TriggerWithId(EndLegTrigger(tick), triggerId), _) =>
      //we have just completed a leg
      //      logDebug(s"Received EndLeg($tick, ${completedLeg.endTime}) for
      // beamVehicleId=${_currentVehicleUnderControl.get.id}, started Boarding/Alighting   ")
      lastVisited = beamServices.geo.wgs2Utm(_currentLeg.get.travelPath.endPoint)
      _currentVehicleUnderControl match {
        case Some(veh) =>
          veh.useFuel(_currentLeg.get.travelPath.distanceInM)
          // If no manager is set, we ignore
          veh.manager.foreach( _ ! NotifyResourceIdle(veh.id,beamServices.geo.wgs2Utm(_currentLeg.get.travelPath.endPoint)))
        case None =>
          throw new RuntimeException(s"Driver $id just ended a leg ${_currentLeg.get} but had no vehicle under control")
      }
      passengerSchedule.schedule.get(_currentLeg.get) match {
        case Some(manifest) =>
          holdTickAndTriggerId(tick, triggerId)
          manifest.riders.foreach { pv =>
            beamServices.personRefs.get(pv.personId).foreach { personRef =>
              logDebug(s"Scheduling NotifyLegEndTrigger for Person $personRef")
              scheduler ! scheduleOne[NotifyLegEndTrigger](tick, personRef, _currentLeg.get)
            }
          }
          if (manifest.alighters.isEmpty) {
            processNextLegOrCompleteMission()
          } else {
            logDebug(s" will wait for ${manifest.alighters.size} alighters: ${manifest.alighters}")
            _awaitingAlightConfirmation ++= manifest.alighters
            stay()
          }
        case None =>
          throw new RuntimeException(s"Driver $id did not find a manifest for BeamLeg ${_currentLeg}")
      }

    case Event(AlightVehicle(tick, vehiclePersonId), _) =>

      // Remove person from vehicle and clear carrier
      _currentVehicleUnderControl.foreach(veh=>
        if(!veh.removePassenger(vehiclePersonId.vehicleId)){
          log.error(s"Attempted to remove passenger ${vehiclePersonId.vehicleId} but was not on board ${id}")
        })
      _awaitingAlightConfirmation -= vehiclePersonId.vehicleId

      if (_awaitingAlightConfirmation.isEmpty) {
        processNextLegOrCompleteMission()
      } else {
        stay()
      }
  }
  chainedWhen(Waiting) {
    case Event(TriggerWithId(StartLegTrigger(tick, newLeg), triggerId), _) =>
      holdTickAndTriggerId(tick, triggerId)
      //      logDebug(s"Received StartLeg($tick, ${newLeg.startTime}) for
      // beamVehicleId=${_currentVehicleUnderControl.get.id} ")

      passengerSchedule.schedule.get(newLeg) match {
        case Some(manifest) =>
          _currentLeg = Some(newLeg)
          manifest.riders.foreach { personVehicle =>
            logDebug(s"Scheduling NotifyLegStartTrigger for Person ${personVehicle.personId}")
            scheduler ! scheduleOne[NotifyLegStartTrigger](tick, beamServices.personRefs
            (personVehicle.personId), newLeg)
          }
          if (manifest.boarders.isEmpty) {
            releaseAndScheduleEndLeg()
          } else {
            logDebug(s" will wait for ${manifest.boarders.size} boarders: ${manifest.boarders}")
            _awaitingBoardConfirmation ++= manifest.boarders
            stay()
          }
        case None =>
          print("")
          stop(Failure(s"Driver $id did not find a manifest for BeamLeg $newLeg"))
      }
    case Event(BoardVehicle(tick, vehiclePersonId), _) =>
      _awaitingBoardConfirmation -= vehiclePersonId.vehicleId
      _currentVehicleUnderControl.foreach{veh=>
        if(!veh.addPassenger(vehiclePersonId.vehicleId)){
          log.error(s"Attempted to add passenger ${vehiclePersonId.vehicleId} but vehicle was full ${id}")
        }
      }
      if (_awaitingBoardConfirmation.isEmpty) {
        releaseAndScheduleEndLeg()
      } else {
        stay()
      }

    case Event(BecomeDriverSuccess(newPassengerSchedule, assignedVehicle), info) =>
      _currentVehicleUnderControl = Some(beamServices.vehicles(assignedVehicle))
      newPassengerSchedule match {
        case Some(passSched) =>
          passengerSchedule = passSched
        case None =>
          passengerSchedule = PassengerSchedule()
      }
      self ! BecomeDriverSuccessAck
      stay()

    case Event(req: CancelReservation, _) =>
      _currentVehicleUnderControl.foreach { vehicleIdAndRef =>
        val vehiclePersonId = VehiclePersonId(vehicleIdAndRef.id, req.passengerId)
        passengerSchedule.removePassenger(vehiclePersonId)
        _awaitingAlightConfirmation -= vehiclePersonId.vehicleId
        _awaitingBoardConfirmation -= vehiclePersonId.vehicleId
        vehicleIdAndRef.driver.get ! CancelReservationWithVehicle(vehiclePersonId)
      }
      stay()
    case Event(TriggerWithId(EndLegTrigger(tick), triggerId), _) =>
      stop(Failure(s"Received EndLegTrigger while in state Waiting. passenger schedule $passengerSchedule"))
  }

  chainedWhen(AnyState) {
    // Note, when this is received from PersonAgent, it is due to a NotifyLegEndTrigger. The reason that
    // NotifyLeg*Trigger
    // are triggers and not direct messages is that otherwise, the schedule would move on before the logic in the
    // following
    // block has time to execute and send the Ack which ultimately results in the next Trigger (e.g. StartLegTrigger)
    // to be scheduled
    case Event(ModifyPassengerSchedule(updatedPassengerSchedule, requestId), _) =>
      var errorFlag = false
      if (!passengerSchedule.isEmpty) {
        val endSpaceTime = passengerSchedule.terminalSpacetime()
        if (updatedPassengerSchedule.initialSpacetime.time < endSpaceTime.time ||
          beamServices.geo.distInMeters(updatedPassengerSchedule.initialSpacetime.loc, endSpaceTime.loc) >
            beamServices.beamConfig.beam.agentsim.thresholdForWalkingInMeters
        ) {
          errorFlag = true
        }
      }
      if (errorFlag) {
        stop(Failure("Invalid attempt to ModifyPassengerSchedule, Spacetime of existing schedule incompatible with " +
          "new"))
      } else {
        passengerSchedule.addLegs(updatedPassengerSchedule.schedule.keys.toSeq)
        updatedPassengerSchedule.schedule.foreach { legAndManifest =>
          legAndManifest._2.riders.foreach { rider =>
            passengerSchedule.addPassenger(rider, Seq(legAndManifest._1))
          }
        }
        val resultingState = _currentLeg match {
          case None =>
            goto(Waiting) replying ModifyPassengerScheduleAck(requestId)
          case Some(_) =>
            stay() replying ModifyPassengerScheduleAck(requestId)
        }
        resultingState
      }
    case Event(ReservationRequestWithVehicle(req, vehicleIdToReserve), _) =>
      val response = if(passengerSchedule.isEmpty){
        log.warning(s"$id received ReservationRequestWithVehicle from ${vehicleIdToReserve} but passengerSchedule is empty")
        ReservationResponse(req.requestId, Left(DriverHasEmptyPassengerScheduleError))
      }else{
        handleVehicleReservation(req, vehicleIdToReserve)
      }
      beamServices.personRefs(req.passengerVehiclePersonId.personId) ! response
      stay()


    case Event(RemovePassengerFromTrip(id),_)=>
      if(passengerSchedule.removePassenger(id)){
//        log.error(s"Passenger $id removed from trip")
      }

      if(_awaitingAlightConfirmation.nonEmpty){
        _awaitingAlightConfirmation -= id.vehicleId
        if (_awaitingAlightConfirmation.isEmpty) {
          processNextLegOrCompleteMission()
        }
      }else if(_awaitingBoardConfirmation.nonEmpty) {
        _awaitingBoardConfirmation -= id.vehicleId
        if (_awaitingBoardConfirmation.isEmpty) {
          releaseAndScheduleEndLeg()
        }
      }
      stay()


    case Event(AddFuel(fuelInJoules),_) =>
      _currentVehicleUnderControl.foreach(_.addFuel(fuelInJoules))
      stay()

    case Event(GetBeamVehicleFuelLevel,_) =>
      _currentVehicleUnderControl match {
        case Some(veh) =>
          sender() !  GetBeamVehicleFuelLevelResult(_currentVehicleUnderControl.get.fuelLevel,lastVisited)
        case None =>
          throw new RuntimeException(s"Some one asked about beam vehicle, but no vehicle under control")
      }
      stay()


  }
  def setPassengerSchedule(newPassengerSchedule: PassengerSchedule) = {
    passengerSchedule = newPassengerSchedule
  }

  def modifyPassengerSchedule(updatedPassengerSchedule: PassengerSchedule)={
    var errorFlag = false
    if (!passengerSchedule.isEmpty) {
      val endSpaceTime = passengerSchedule.terminalSpacetime()
      if (updatedPassengerSchedule.initialSpacetime.time < endSpaceTime.time ||
        beamServices.geo.distInMeters(updatedPassengerSchedule.initialSpacetime.loc, endSpaceTime.loc) >
          beamServices.beamConfig.beam.agentsim.thresholdForWalkingInMeters
      ) {
        errorFlag = true
      }
    }
    if (errorFlag) {
      stop(Failure("Invalid attempt to ModifyPassengerSchedule, Spacetime of existing schedule incompatible with " +
        "new"))
    } else {
      passengerSchedule.addLegs(updatedPassengerSchedule.schedule.keys.toSeq)
      updatedPassengerSchedule.schedule.foreach { legAndManifest =>
        legAndManifest._2.riders.foreach { rider =>
          passengerSchedule.addPassenger(rider, Seq(legAndManifest._1))
        }
      }
    }
  }

  def becomeDriverOfVehicle(vehicleId: Id[Vehicle], tick: Double) = {
    val vehicle = beamServices.vehicles(vehicleId)
    vehicle.becomeDriver(self).fold(fa =>
      stop(Failure(s"BeamAgent $self attempted to become driver of vehicle $id " +
        s"but driver ${vehicle.driver.get} already assigned.")),
      fb => {
        _currentVehicleUnderControl = Some(vehicle)
        eventsManager.processEvent(new PersonEntersVehicleEvent(tick, Id.createPersonId(id), vehicleId))
      })
  }

  def resumeControlOfVehcile(vehicleId: Id[Vehicle]) = {
    _currentVehicleUnderControl = Some(beamServices.vehicles(vehicleId))
  }

  def unbecomeDriverOfVehicle(vehicleId: Id[Vehicle], tick: Double): Unit ={
    beamServices.vehicles(vehicleId).unsetDriver()
    eventsManager.processEvent(new PersonLeavesVehicleEvent(tick, Id.createPersonId(id), vehicleId))
  }

  private def releaseAndScheduleEndLeg(): FSM.State[BeamAgent.BeamAgentState, BeamAgent.BeamAgentInfo[T]] = {
    val (tick, theTriggerId) = releaseTickAndTriggerId()
    eventsManager.processEvent(new VehicleEntersTrafficEvent(tick, Id.createPersonId(id), null, _currentVehicleUnderControl.get.id, "car", 1.0))
    // Produce link events for this trip (the same ones as in PathTraversalEvent).
    // TODO: They don't contain correct timestamps yet, but they all happen at the end of the trip!!
    // So far, we only throw them for ExperiencedPlans, which don't need timestamps.
    RoutingModel.traverseStreetLeg(_currentLeg.get, _currentVehicleUnderControl.get.id, (_,_) => 0L)
      .foreach(eventsManager.processEvent)
    eventsManager.processEvent(new VehicleLeavesTrafficEvent(_currentLeg.get.endTime, id.asInstanceOf[Id[Person]], null, _currentVehicleUnderControl.get.id, "car", 0.0))
    scheduler ! completed(theTriggerId, schedule[EndLegTrigger](_currentLeg.get.endTime, self))
    goto(Moving)
  }

  private def processNextLegOrCompleteMission() = {
    val (theTick, theTriggerId) = releaseTickAndTriggerId()
    eventsManager.processEvent(new PathTraversalEvent(theTick, _currentVehicleUnderControl.get.id,
      _currentVehicleUnderControl.get.getType,
      passengerSchedule.curTotalNumPassengers(_currentLeg.get),
      _currentLeg.get))

    _currentLeg = None
    passengerSchedule.schedule.remove(passengerSchedule.schedule.firstKey)

    if (passengerSchedule.schedule.nonEmpty) {
      val nextLeg = passengerSchedule.schedule.firstKey
      scheduler ! completed(theTriggerId, schedule[StartLegTrigger](nextLeg.startTime, self, nextLeg))
      goto(Waiting)
    } else {
      passengerScheduleEmpty(theTick, theTriggerId)
    }
  }

  private def handleVehicleReservation(req: ReservationRequest, vehicleIdToReserve: Id[Vehicle]) = {
    val response = _currentLeg match {
      case Some(currentLeg) if req.departFrom.startTime <= currentLeg.startTime =>
        ReservationResponse(req.requestId, Left(VehicleGoneError))
      case _ =>
        if(req.departFrom.startTime < passengerSchedule.schedule.head._1.startTime){
          ReservationResponse(req.requestId, Left(VehicleGoneError))
        }else{
          val tripReservations = passengerSchedule.schedule.from(req.departFrom).to(req.arriveAt).toVector
          val vehicleCap = _currentVehicleUnderControl.get.getType.getCapacity
          val fullCap = vehicleCap.getSeats + vehicleCap.getStandingRoom
          val hasRoom = tripReservations.forall { entry =>
            entry._2.riders.size < fullCap
          }
          if(hasRoom){
            val legs = tripReservations.map(_._1)
            passengerSchedule.addPassenger(req.passengerVehiclePersonId, legs)
            ReservationResponse(req.requestId, Right(ReserveConfirmInfo(req.departFrom, req.arriveAt, req.passengerVehiclePersonId)))
          } else {
            ReservationResponse(req.requestId, Left(VehicleFullError))
          }
        }
    }
    response
  }

}
