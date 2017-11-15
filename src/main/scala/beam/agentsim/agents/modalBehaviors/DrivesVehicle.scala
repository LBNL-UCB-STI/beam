package beam.agentsim.agents.modalBehaviors

import akka.actor.FSM
import beam.agentsim.Resource.TellManagerResourceIsAvailable
import akka.actor.FSM.Failure
import beam.agentsim.agents.BeamAgent
import beam.agentsim.agents.BeamAgent.{AnyState, BeamAgentData}
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.TriggerUtils._
import beam.agentsim.agents.modalBehaviors.DrivesVehicle._
import beam.agentsim.agents.vehicles.AccessErrorCodes.{VehicleGoneError, VehicleNotUnderControlError}
import beam.agentsim.agents.vehicles.VehicleProtocol._
import beam.agentsim.agents.vehicles._
import beam.agentsim.agents.TriggerUtils._
import beam.agentsim.agents.modalBehaviors.DrivesVehicle._
import beam.agentsim.agents.vehicles.{PassengerSchedule, VehiclePersonId}
import beam.agentsim.events.AgentsimEventsBus.MatsimEvent
import beam.agentsim.events.{PathTraversalEvent, SpaceTime}
import beam.agentsim.scheduler.{Trigger, TriggerWithId}
import beam.router.RoutingModel.BeamLeg
import beam.router.r5.NetworkCoordinator
import beam.sim.HasServices
import org.matsim.api.core.v01.events.{PersonEntersVehicleEvent, PersonLeavesVehicleEvent}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.vehicles.Vehicle

import scala.collection.immutable.HashSet
import scala.util.{Failure, Success}

/**
  * @author dserdiuk on 7/29/17.
  */
object DrivesVehicle {

  case class StartLegTrigger(tick: Double, beamLeg: BeamLeg) extends Trigger

  case class EndLegTrigger(tick: Double, beamLeg: BeamLeg) extends Trigger

  case class NotifyLegEndTrigger(tick: Double, beamLeg: BeamLeg) extends Trigger

  case class NotifyLegStartTrigger(tick: Double, beamLeg: BeamLeg) extends Trigger

}

trait DrivesVehicle[T <: BeamAgentData] extends BeamAgent[T] with HasServices {

  //TODO: double check that mutability here is legit espeically with the schedules passed in
  protected var passengerSchedule: PassengerSchedule = PassengerSchedule()

  protected var _currentLeg: Option[BeamLeg] = None
  //TODO: send some message to set _currentVehicle
  protected var _currentVehicleUnderControl: Option[BeamVehicle] = None
  protected var _awaitingBoardConfirmation: Set[Id[Vehicle]] = HashSet[Id[Vehicle]]()
  protected var _awaitingAlightConfirmation: Set[Id[Vehicle]] = HashSet[Id[Vehicle]]()


  protected var pendingReservations: List[ReservationRequest] = List[ReservationRequest]()

  chainedWhen(Moving) {
    case Event(TriggerWithId(EndLegTrigger(tick, completedLeg), triggerId), _) =>
      //we have just completed a leg
      logDebug(s"Received EndLeg($tick, ${completedLeg.endTime}) for beamVehicleId=${
        _currentVehicleUnderControl.get
          .id
      }, started Boarding/Alighting   ")
      passengerSchedule.schedule.get(completedLeg) match {
        case Some(manifest) =>
          holdTickAndTriggerId(tick, triggerId)
          manifest.riders.foreach { pv =>
            beamServices.personRefs.get(pv.personId).foreach { personRef =>
              logDebug(s"Scheduling NotifyLegEndTrigger for Person $personRef")
              beamServices.schedulerRef ! scheduleOne[NotifyLegEndTrigger](tick, personRef, completedLeg)
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
    case Event(AlightingConfirmation(vehicleId), _) =>
      _awaitingAlightConfirmation -= vehicleId
      if (_awaitingAlightConfirmation.isEmpty) {
        processNextLegOrCompleteMission()
      } else {
        stay()
      }
  }

  chainedWhen(Waiting) {
    case Event(TriggerWithId(StartLegTrigger(tick, newLeg), triggerId), agentInfo) =>
      holdTickAndTriggerId(tick,triggerId)
      logDebug(s"Received StartLeg($tick, ${newLeg.startTime}) for beamVehicleId=${_currentVehicleUnderControl.get.id} ")

      passengerSchedule.schedule.get(newLeg) match {
        case Some(manifest) =>
          _currentLeg = Some(newLeg)
          //TODO: where should this get sent?
          //          _currentVehicleUnderControl.get.ref ! AppendToTrajectory(newLeg.travelPath)
          manifest.riders.foreach { personVehicle =>
            logDebug(s"Scheduling NotifyLegStartTrigger for Person ${personVehicle.personId}")
            beamServices.schedulerRef ! scheduleOne[NotifyLegStartTrigger](tick, beamServices.personRefs
            (personVehicle.personId), newLeg)
          }
          if (manifest.boarders.isEmpty) {
            releaseAndScheduleEndLeg()
          } else {
            _awaitingBoardConfirmation ++= manifest.boarders
            stay()
          }
        case None =>
          stop(Failure(s"Driver $id did not find a manifest for BeamLeg $newLeg"))
      }

    case Event(EnterVehicle(tick, newPassengerVehicle), _) =>
      _currentVehicleUnderControl.foreach(veh => {
        veh.vehicleOccupancyAdministrator.addPassenger(newPassengerVehicle.vehicleId) match {
          case Left(vehicleCapacityExceeded) => sender() ! vehicleCapacityExceeded
          case Right(setCarrier) => sender() ! setCarrier
            beamServices.agentSimEventsBus.publish(
              MatsimEvent(
                new PersonEntersVehicleEvent(tick, newPassengerVehicle.personId, newPassengerVehicle.vehicleId))
            )
        }
      })
      stay()

    case Event(ExitVehicle(tick, passengerVehicleId), _) =>
      _currentVehicleUnderControl.map(veh =>
        veh.vehicleOccupancyAdministrator.removePassenger(passengerVehicleId.vehicleId) match {
          case Failure(ex) =>
            goto(BeamAgent.Error) using stateData.copy(errorReason = Some(ex.getMessage))
          case Success(_) =>
            beamServices.beamVehicles(passengerVehicleId.vehicleId).carrier = None
            logDebug(s"Passenger $passengerVehicleId alighted from vehicleId=$id")
            beamServices.agentSimEventsBus.publish(MatsimEvent(
              new PersonLeavesVehicleEvent(tick, passengerVehicleId.personId, passengerVehicleId.vehicleId)))
            stay()
        }
      ).getOrElse(
        goto(BeamAgent.Error) using stateData.copy(
          errorReason = Some("No vehicle under driver control, but received request to exit vehicle!"))
      )

    case Event(BoardingConfirmation(vehicleId), _) =>
      _awaitingBoardConfirmation -= vehicleId
      if (_awaitingBoardConfirmation.isEmpty) {
        releaseAndScheduleEndLeg()
      } else {
        stay()
      }

    case Event(BecomeDriverSuccess(newPassengerSchedule, assignedVehicle), info) =>
      _currentVehicleUnderControl = Option(assignedVehicle)
      newPassengerSchedule match {
        case Some(passSched) =>
          passengerSchedule = passSched
        case None =>
          passengerSchedule = PassengerSchedule()
      }
      self ! BecomeDriverSuccessAck
      stay()

  }

  chainedWhen(AnyState) {
    // Problem, when this is received from PersonAgent, it is due to a NotifyEndLeg trigger which doesn't have an ack
    // So the schedule has moved ahead before this can schedule a new StartLegTrigger, so maybe Notify*Leg should be
    // Triggers?
    case Event(ModifyPassengerSchedule(updatedPassengerSchedule, requestId), _) =>
      var errorFlag = false
      if (!passengerSchedule.isEmpty) {
        val endSpaceTime = passengerSchedule.terminalSpacetime()
        if (updatedPassengerSchedule.initialSpacetime().time < endSpaceTime.time ||
          beamServices.geo.distInMeters(updatedPassengerSchedule.initialSpacetime().loc, endSpaceTime.loc) >
            beamServices.beamConfig.beam.agentsim.thresholdForWalkingInMeters
        ) {
          errorFlag = true
        }
      }
      if (errorFlag) {
        stop(Failure("Invalid attempt to ModifyPassengerSchedule, Spacetime of existing schedule " +
          "incompatible with new"))
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
      require(passengerSchedule.schedule.nonEmpty, "Driver needs to init list of stops")
      logDebug(s"Received Reservation(vehicle=$vehicleIdToReserve, boardingLeg=${req.departFrom.startTime}, " +
        s"alighting=${req.arriveAt.startTime}) ")

      val response = handleVehicleReservation(req, vehicleIdToReserve)
      beamServices.personRefs(req.passengerVehiclePersonId.personId) ! response
      stay()

    // XXXX (VR): from vehicle's CancelReservation
    case Event(RemovePassengerFromTrip(id), _) => {
      if (passengerSchedule.removePassenger(id)) {
        log.error(s"Passenger $id removed from trip")
      }

      if (_awaitingAlightConfirmation.nonEmpty) {
        _awaitingAlightConfirmation -= id.vehicleId
        if (_awaitingAlightConfirmation.isEmpty) {
          processNextLegOrCompleteMission()
        }
      } else if (_awaitingBoardConfirmation.nonEmpty) {
        _awaitingBoardConfirmation -= id.vehicleId
        if (_awaitingBoardConfirmation.isEmpty) {
          releaseAndScheduleEndLeg()
        }
      }
      stay()
    }

    case Event(TellManagerResourceIsAvailable(whenWhere: SpaceTime), _) =>
      _currentVehicleUnderControl.foreach(veh => veh.informManagerResourceIsAvailable(whenWhere))
      stay()
  }

  private def releaseAndScheduleEndLeg(): FSM.State[BeamAgent.BeamAgentState, BeamAgent.BeamAgentInfo[T]] = {
    val (_, theTriggerId) = releaseTickAndTriggerId()
    beamServices.schedulerRef ! completed(theTriggerId, schedule[EndLegTrigger](_currentLeg.get.endTime, self,
      _currentLeg.get))
    goto(Moving)
  }

  private def getLinks: Vector[String] = {
    val pathLinks: Vector[String] = _currentLeg match {
      case Some(leg) =>
        leg.travelPath.linkIds
      case None =>
        Vector()
    }
    pathLinks
  }


  private def getStartCoord: Coord = {
    val startCoord = try {
      val r5Coord = NetworkCoordinator.transportNetwork.streetLayer.edgeStore.getCursor(getLinks.head.toInt)
        .getGeometry.getCoordinate
      Some(new Coord(r5Coord.x, r5Coord.y))
    } catch {
      case e: Exception => None
    }
    startCoord.orNull
  }

  private def getEndCoord(): Coord = {
    val endCoord: Option[Coord] = try {
      val r5Coord = NetworkCoordinator.transportNetwork.streetLayer.edgeStore.getCursor(getLinks(getLinks.size -
        1).toInt).getGeometry.getCoordinate
      Some(new Coord(r5Coord.x, r5Coord.y))
    } catch {
      case e: Exception => None
    }
    endCoord.orNull
  }

  private def processNextLegOrCompleteMission() = {
    val (theTick, theTriggerId) = releaseTickAndTriggerId()

    beamServices.agentSimEventsBus.publish(MatsimEvent(new PathTraversalEvent(theTick, _currentVehicleUnderControl
      .get.id,
      beamServices.beamVehicles(_currentVehicleUnderControl.get.id).getType,
      passengerSchedule.curTotalNumPassengers(_currentLeg.get),
      _currentLeg.get, getStartCoord, getEndCoord())))

    _currentLeg = None
    passengerSchedule.schedule.remove(passengerSchedule.schedule.firstKey)
    if (passengerSchedule.schedule.nonEmpty) {
      val nextLeg = passengerSchedule.schedule.firstKey
      beamServices.schedulerRef ! completed(theTriggerId, schedule[StartLegTrigger](nextLeg.startTime, self, nextLeg))
      goto(Waiting)
    } else {
      beamServices.schedulerRef ! completed(theTriggerId, schedule[PassengerScheduleEmptyTrigger](theTick, self))
      goto(Waiting)
    }
  }

  def errorFromDrivesVehicle(reason: String, triggerId: Long): DrivesVehicle.this.State = {
    logError(s"Erroring: From DrivesVehicle $id, reason: ${_errorMessageFromDrivesVehicle}")
    if (triggerId >= 0) beamServices.schedulerRef ! completed(triggerId)
    goto(BeamAgent.Error) using stateData.copy(errorReason = Some(reason))
  }


  private def handleVehicleReservation(req: ReservationRequest, vehicleIdToReserve: Id[Vehicle]): ReservationResponse
  = {
    // XXXX This is really quite beautiful functional code!
    _currentLeg match {
      case Some(currentLeg) if req.departFrom.startTime <= currentLeg.startTime =>
        ReservationResponse(req.requestId, Left(VehicleGoneError))
      case None =>
        val tripReservations = passengerSchedule.schedule.from(req.departFrom).to(req.arriveAt).toVector
        ReservationResponse(req.requestId,
          _currentVehicleUnderControl.map(_.vehicleOccupancyAdministrator
            .addPassenger(req
              .passengerVehiclePersonId.vehicleId).flatMap({ _ =>
            val legs = tripReservations.map(_._1)
            passengerSchedule.addPassenger(req.passengerVehiclePersonId, legs)
            Right(ReserveConfirmInfo(req.departFrom, req.arriveAt, req.passengerVehiclePersonId))
          })).getOrElse(
            // In case vehicle not currently under your control!
            Left(VehicleNotUnderControlError))
        )
    }
  }

  private def sendPendingReservations(): Unit = {
    if (pendingReservations.nonEmpty) {
      pendingReservations.foreach { _ =>
        //FIXME: process reservations directly here... happens upon becoming the driver

        //        driverActor ! ReservationRequestWithVehicle(reservation, id)
      }
      pendingReservations = List()
    }
  }

}


