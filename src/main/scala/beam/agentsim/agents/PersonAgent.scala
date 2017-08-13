package beam.agentsim.agents

import akka.actor.{ActorRef, Props}
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.modalBehaviors.{ChoosesMode, DrivesVehicle}
import beam.agentsim.agents.modalBehaviors.ChoosesMode.BeginModeChoiceTrigger
import beam.agentsim.agents.modalBehaviors.DrivesVehicle.{NotifyLegEnd, NotifyLegStart, StartLegTrigger}
import beam.agentsim.agents.vehicles.BeamVehicle.{BecomeDriver, BecomeDriverSuccess, EnterVehicle, ExitVehicle, ResetCarrier}
import beam.agentsim.agents.vehicles.{HumanBodyVehicle, PassengerSchedule, VehiclePersonId, VehicleStack}
import beam.agentsim.events.AgentsimEventsBus.MatsimEvent
import beam.agentsim.events.{PathTraversalEvent, SpaceTime}
import beam.agentsim.scheduler.BeamAgentScheduler._
import beam.agentsim.scheduler.{Trigger, TriggerWithId}
import beam.router.RoutingModel._
import beam.sim.{BeamServices, HasServices}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events._
import org.matsim.api.core.v01.population._
import org.matsim.vehicles.Vehicle
import org.slf4j.LoggerFactory

import scala.collection.mutable

/**
  */
object PersonAgent {

  private val ActorPrefixName = "person-"
  val timeToChooseMode: Double = 0.0
  val minActDuration: Double = 0.0
  val teleportWalkDuration = 0.0

  private val logger = LoggerFactory.getLogger(classOf[PersonAgent])

  // syntactic sugar for props creation
  def props(services: BeamServices, personId: Id[PersonAgent], personData: PersonData) = {
      Props(new PersonAgent(services, personId, personData))
  }
  def buildActorName(personId: Id[Person]): String = {
    s"$ActorPrefixName${personId.toString}"
  }

  //////////////////////////////
  // PersonData Begin... //
  /////////////////////////////
  object PersonData {
    import scala.collection.JavaConverters._

    /**
      * [[PersonData]] factory method to assist in  creating [[PersonData]].
      * Permits creation of
      *
      * @param plan : The plan having at least some `Activities`
      * @return `PersonData`
      */
    def apply(plan: Plan, bodyId: Id[Vehicle]): PersonData = defaultPersonData(planToVec(plan), bodyId)

    def apply(plan: Plan): PersonData = defaultPersonData(planToVec(plan))

    def apply(activities:Vector[Activity]):PersonData = defaultPersonData(activities)

    def planToVec(plan: Plan): Vector[Activity] = {
      scala.collection.immutable.Vector.empty[Activity] ++ plan.getPlanElements.asScala.filter(p => p.isInstanceOf[Activity]).map(p => p.asInstanceOf[Activity])
    }

    implicit def plan2PersonData(plan: Plan): PersonData = PersonData(plan)

    def defaultPersonData(vector: Vector[Activity]):PersonData = {
      PersonData(vector, 0, BeamTrip.empty, Vector[BeamTrip](),  VehicleStack(), None)
    }
    def defaultPersonData(vector: Vector[Activity], bodyId: Id[Vehicle]):PersonData = {
      PersonData(vector, 0, BeamTrip.empty, Vector[BeamTrip](),  VehicleStack(), Some(bodyId))
    }

  }

  case class PersonData(activityChain: Vector[Activity], currentActivityIndex: Int = 0,
                        currentRoute: BeamTrip = BeamTrip.empty,
                        currentAlternatives: Vector[BeamTrip] = Vector[BeamTrip](),
                        currentVehicle: VehicleStack,
                        humanBodyVehicle: Option[Id[Vehicle]]) extends BeamAgentData {

    def activityOrMessage(ind: Int, msg: String): Either[String, Activity] = {
      if (ind < 0 || ind >= activityChain.length) Left(msg) else Right(activityChain(ind))
    }

    def currentActivity: Activity = activityChain(currentActivityIndex)

    def nextActivity: Either[String, Activity] = {
      activityOrMessage(currentActivityIndex + 1, "plan finished")
    }

    def prevActivity: Either[String, Activity] = {
      activityOrMessage(currentActivityIndex - 1, "at start")
    }


  }


  // End PersonData ~

  sealed trait InActivity extends BeamAgentState

  case object PerformingActivity extends InActivity {
    override def identifier = "Performing an Activity"
  }

  sealed trait Traveling extends BeamAgentState

  case object ChoosingMode extends Traveling {
    override def identifier = "ChoosingMode"
  }

  case object Waiting extends Traveling {
    override def identifier = "Waiting"
  }

  case object Moving extends Traveling {
    override def identifier = "Moving"
  }

  case class ResetPersonAgent(tick: Double) extends Trigger

  case class ActivityStartTrigger(tick: Double) extends Trigger

  case class ActivityEndTrigger(tick: Double) extends Trigger

  case class RouteResponseWrapper(tick: Double, triggerId: Long, alternatives: Vector[BeamTrip]) extends Trigger

  case class TaxiInquiryResponseWrapper(tick: Double, triggerId: Long, alternatives: Vector[BeamTrip], timesToCustomer: Vector[Double]) extends Trigger

  case class ReserveTaxiResponseWrapper(tick: Double, triggerId: Long, taxi: Option[ActorRef], timeToCustomer: Double, tripChoice: BeamTrip) extends Trigger

  case class FinishWrapper(tick: Double, triggerId: Long) extends Trigger

  case class NextActivityWrapper(tick: Double, triggerId: Long) extends Trigger

  case class PersonDepartureTrigger(tick: Double) extends Trigger

  case class PersonEntersTaxiTrigger(tick: Double) extends Trigger

  case class PersonLeavesTaxiTrigger(tick: Double) extends Trigger

  case class PersonEntersBoardingQueueTrigger(tick: Double) extends Trigger

  case class PersonEntersAlightingQueueTrigger(tick: Double) extends Trigger

  case class PersonArrivesTransitStopTrigger(tick: Double) extends Trigger

  case class PersonArrivalTrigger(tick: Double) extends Trigger

  case class TeleportationArrivalTrigger(tick: Double) extends Trigger

  case class CompleteDrivingMissionTrigger(tick: Double) extends Trigger


}

class PersonAgent(val beamServices: BeamServices, override val id: Id[PersonAgent], override val data: PersonData) extends BeamAgent[PersonData] with
  TriggerShortcuts with HasServices with CanUseTaxi with ChoosesMode with DrivesVehicle[PersonData] {

  when(PerformingActivity) {
    case ev@Event(_, _) =>
      handleEvent(stateName, ev)
  }
  when(ChoosingMode) {
    case ev@Event(_, _) =>
      handleEvent(stateName, ev)
    case msg@_ =>
      logError(s"Unrecognized message ${msg}")
      goto(Error)
  }
  when(Waiting) {
    case ev@Event(_, _) =>
      handleEvent(stateName, ev)
    case msg@_ =>
      logError(s"Unrecognized message ${msg}")
      goto(Error)
  }
  when(Moving) {
    case ev@Event(_, _) =>
      handleEvent(stateName, ev)
    case msg@_ =>
      logError(s"Unrecognized message ${msg}")
      goto(Error)
  }
  whenUnhandled{
    case ev@Event(_, _) =>
      handleEvent(AnyState, ev)
    case msg@_ =>
      logError(s"Unrecognized message ${msg}")
      goto(Error)
  }

  chainedWhen(Uninitialized){
    case Event(TriggerWithId(InitializeTrigger(tick), triggerId), _) =>
//      services.schedulerRef ! ScheduleTrigger(ActivityStartTrigger(0.0), self)
      goto(Initialized) replying completed(triggerId,schedule[ActivityStartTrigger](0.0,self))
  }
  chainedWhen(Initialized) {
    case Event(TriggerWithId(ActivityStartTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val currentActivity = info.data.currentActivity
      beamServices.agentSimEventsBus.publish(MatsimEvent(new ActivityStartEvent(tick, id, currentActivity.getLinkId, currentActivity.getFacilityId, currentActivity.getType)))
      // Since this is the first activity of the day, we don't increment the currentActivityIndex
      logInfo(s"starting at ${currentActivity.getType} @ $tick")
      goto(PerformingActivity) using info replying completed(triggerId, schedule[ActivityEndTrigger](currentActivity.getEndTime, self))
  }
  chainedWhen(PerformingActivity) {
    case Event(TriggerWithId(ActivityEndTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val currentActivity = info.data.currentActivity
      beamServices.agentSimEventsBus.publish(MatsimEvent(new ActivityEndEvent(tick, id, currentActivity.getLinkId, currentActivity.getFacilityId, currentActivity.getType)))

      info.data.nextActivity.fold(
        msg => {
          logInfo(s"didn't get nextActivity because $msg")
          self ! FinishWrapper(tick, triggerId)
        },
        nextAct => {
          logInfo(s"going to ${nextAct.getType} @ ${tick}")
        }
      )
      goto(ChoosingMode) using info replying completed(triggerId,schedule[BeginModeChoiceTrigger](tick, self))
    case Event(msg: FinishWrapper, info: BeamAgentInfo[PersonData]) =>
      beamServices.schedulerRef ! CompletionNotice(msg.triggerId)
      logError("FinishWrapper recieved while in PerformingActivity")
      goto(Error)
  }

  chainedWhen(Waiting) {
    /*
     * Starting Trip
     */
    case Event(TriggerWithId(PersonDepartureTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      processNextLegOrStartActivity(triggerId, tick, info.data)
    /*
     * Complete leg(s) as driver
     */
    case Event(TriggerWithId(CompleteDrivingMissionTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      processNextLegOrStartActivity(triggerId, tick, info.data)
    /*
     * Learn as passenger that leg is starting
     */
    case Event(NotifyLegStart(tick), info: BeamAgentInfo[PersonData]) =>
      val processedDataOpt = breakTripIntoNextLegAndRestOfTrip(info.data.currentRoute, tick)
      processedDataOpt match {
        case Some(processedData) =>
          val nextBeamVehicleId = processedData.nextVehicleAssignment.beamVehicleId
          val previousVehicleId = info.data.currentVehicle.nestedVehicles.head
          val newCarrierVehicleRef = beamServices.vehicleRefs(nextBeamVehicleId)
          newCarrierVehicleRef ! EnterVehicle(tick, previousVehicleId)
          goto(Moving) using BeamAgentInfo(id, info.data.copy(currentRoute = processedData.restTrip, currentVehicle = info.data.currentVehicle.push(nextBeamVehicleId)))
        case None =>
          logError(s"Expected a non-empty BeamTrip but found ${info.data.currentRoute}")
          goto(Error)
      }

    case ev@Event(_,_) =>
      logError(s"Unrecognized event ${ev}")
      goto(Error)
  }
  chainedWhen(Moving) {
    /*
     * Learn as passenger that leg is ending
     */
    case Event(NotifyLegEnd(tick), info: BeamAgentInfo[PersonData]) =>
      val processedDataOpt = breakTripIntoNextLegAndRestOfTrip(info.data.currentRoute, tick)
      processedDataOpt match {
        case Some(processedData) => // There are more legs in the trip...
          if(processedData.nextVehicleAssignment.beamVehicleId == info.data.currentVehicle.outermostVehicle()){
            // The next vehicle is the same as current so we do nothing
            stay()
          }else{
            // The next vehicle is different from current so we exit the current vehicle
            val passengerVehicleId = info.data.currentVehicle.penultimateVehicle()
            beamServices.vehicleRefs(info.data.currentVehicle.outermostVehicle()) ! ExitVehicle(tick, passengerVehicleId)
            // Note that this will send a scheduling reply to a driver, not the scheduler, the driver must pass on the new trigger
            processNextLegOrStartActivity(-1L,tick,info.data)
          }
        case None =>
          logError(s"Expected a non-empty BeamTrip but found ${info.data.currentRoute}")
          goto(Error)
      }

  }

  /*
   * processNextLegOrStartActivity
   *
   * This should be called when it's time to either embark on another leg in a trip or to wrap up a trip that is
   * now complete. There are four outcomes possible:
   *
   * 1 There are more legs in the trip and the PersonAgent is the driver => stay in current state but schedule StartLegTrigger
   * 2 There are more legs in the trip but the PersonAGent is a passenger => goto Waiting and schedule nothing further (the driver will initiate the start of the leg)
   * 3 The trip is over and there are no more activities in the agent plan => goto Finished
   * 4 The trip is over and there are more activities in the agent plan => goto PerformingActivity and schedule end of activity
   */
  def processNextLegOrStartActivity(triggerId: Long, tick: Double, data: PersonData): PersonAgent.this.State = {
    if(data.currentRoute.legs.nonEmpty){
      val processedDataOpt = breakTripIntoNextLegAndRestOfTrip(data.currentRoute, tick)
      processedDataOpt match {
        case Some(processedData) =>
          if(processedData.nextVehicleAssignment.asDriver){
            val passengerSchedule = PassengerSchedule()
            val vehicleId = if(data.humanBodyVehicle.isDefined && HumanBodyVehicle.isHumanBodyVehicle(processedData.nextVehicleAssignment.beamVehicleId)){
              data.humanBodyVehicle.get
            }else{
              processedData.nextVehicleAssignment.beamVehicleId
            }
            //TODO the following needs to find all subsequent legs in currentRoute for which this agent is driver and vehicle is the same...
            val nextLeg = processedData.nextLeg
            passengerSchedule.addLegs(Vector(nextLeg))
            beamServices.vehicleRefs(vehicleId) ! BecomeDriver(tick, id, Some(passengerSchedule))
            val newStateBeamInfo = BeamAgentInfo(id, data.copy(currentRoute = processedData.restTrip))
            stay() using newStateBeamInfo replying completed(triggerId,schedule[StartLegTrigger](nextLeg.startTime,self,nextLeg))
          }else{
            // We don't update PersonData with the rest of the currentRoute, this will happen when the agent recieves the NotifyStartLeg message
            goto(Waiting) replying completed(triggerId)
          }
        case None =>
          logError(s"Expected a non-empty BeamTrip but found ${data.currentRoute}")
          goto(Error)
      }
    }else{
      data.nextActivity match {
        case Left(msg) =>
          logDebug(msg)
          goto(Finished) replying completed(triggerId)
        case Right(activity) =>
          goto(PerformingActivity) replying completed(triggerId, schedule[ActivityEndTrigger](activity.getEndTime, self))
      }
    }
  }

  /*
   *  Never attempt to send triggers to the scheduler from inside onTransition. This opens up the
   *  possibility that a trigger is scheduled in the past due to the actor system taking too long
   *  for the message to make it to the scheduler.
   */
//  onTransition {
//    case Uninitialized -> Initialized =>
//    case _ -> ChoosingMode =>
//      logInfo(s"entering ChoosingMode")
//    case ChoosingMode -> Walking =>
//      logInfo(s"going from ChoosingMode to Walking")
//    case Walking -> Driving =>
//      logInfo(s"going from Walking to Driving")
//    case Driving -> Walking =>
//      logInfo(s"going from Driving to Walking")
//    case Walking -> PerformingActivity =>
//      logInfo(s"going from Walking to PerformingActivity")
//  }

  override def logPrefix(): String = s"PersonAgent $id: "

  // NEVER use stateData in below, pass `info` object directly (closure around stateData on object creation)

  private def breakTripIntoNextLegAndRestOfTrip(trip: BeamTrip, tick: Double): Option[ProcessedData] = {
    if(trip.legs.isEmpty){
      None
    }else{
      val nextLeg = trip.legs.keys.head
      val nextVehicleAssignment = trip.legs(nextLeg)
      val restLegs = trip.legs - nextLeg
      val restTrip: BeamTrip = BeamTrip(restLegs, trip.accessMode)
      val nextStart = if (restTrip.legs.nonEmpty) {
        restTrip.legs.keys.head.startTime
      } else {
        tick
      }
      Some(ProcessedData(nextLeg, nextVehicleAssignment, restTrip, nextStart))
    }
  }

  case class ProcessedData(nextLeg: BeamLeg, nextVehicleAssignment: BeamVehicleAssignment, restTrip: BeamTrip, nextStart: Double)

  private def publishPathTraversal(event: PathTraversalEvent): Unit = {
    //TODO: convert pathTraversalEvents to hashset
    if (beamServices.beamConfig.beam.events.pathTraversalEvents contains event.beamLeg.mode.value.toLowerCase()) {
      beamServices.agentSimEventsBus.publish(MatsimEvent(event))
    }
  }

}


