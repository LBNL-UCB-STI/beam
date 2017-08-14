package beam.agentsim.agents

import akka.actor.{ActorRef, Props}
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.modalBehaviors.{ChoosesMode, DrivesVehicle, RidesInVehicles}
import beam.agentsim.agents.modalBehaviors.ChoosesMode.BeginModeChoiceTrigger
import beam.agentsim.agents.modalBehaviors.DrivesVehicle.{NotifyLegEnd, NotifyLegStart, StartLegTrigger}
import beam.agentsim.agents.vehicles.BeamVehicle.{BecomeDriver, BecomeDriverSuccess, EnterVehicle, ExitVehicle}
import beam.agentsim.agents.vehicles.{HumanBodyVehicle, PassengerSchedule, VehicleStack}
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
  def props(services: BeamServices, personId: Id[PersonAgent], plan: Plan, humanBodyVehicleId: Id[Vehicle]) = {
      Props(new PersonAgent(services, personId, plan, humanBodyVehicleId))
  }
  def buildActorName(personId: Id[Person]): String = {
    s"$ActorPrefixName${personId.toString}"
  }

  case class PersonData() extends BeamAgentData {}
  object PersonData {
    import scala.collection.JavaConverters._

    def planToVec(plan: Plan): Vector[Activity] = {
      scala.collection.immutable.Vector.empty[Activity] ++ plan.getPlanElements.asScala.filter(p => p.isInstanceOf[Activity]).map(p => p.asInstanceOf[Activity])
    }
  }

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

class PersonAgent(val beamServices: BeamServices, override val id: Id[PersonAgent], val matsimPlan: Plan, humanBodyVehicleId: Id[Vehicle], override val data: PersonData = PersonData()) extends BeamAgent[PersonData] with
  TriggerShortcuts with HasServices with CanUseTaxi with ChoosesMode with DrivesVehicle[PersonData] {

  var _activityChain: Vector[Activity] = Vector()
  var _currentActivityIndex: Int = 0
  var _currentAlternatives: Vector[BeamTrip] = Vector[BeamTrip]()
  var _currentVehicle: VehicleStack = VehicleStack()
  var _humanBodyVehicle: Option[Id[Vehicle]] = None
  var _currentRoute: EmbodiedBeamTrip = EmbodiedBeamTrip.empty

  def activityOrMessage(ind: Int, msg: String): Either[String, Activity] = {
    if (ind < 0 || ind >= _activityChain.length) Left(msg) else Right(_activityChain(ind))
  }
  def currentActivity: Activity = _activityChain(_currentActivityIndex)
  def nextActivity: Either[String, Activity] = {
    activityOrMessage(_currentActivityIndex + 1, "plan finished")
  }
  def prevActivity: Either[String, Activity] = {
    activityOrMessage(_currentActivityIndex - 1, "at start")
  }

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

  chainedWhen(Uninitialized){
    case Event(TriggerWithId(InitializeTrigger(tick), triggerId), _) =>
//      services.schedulerRef ! ScheduleTrigger(ActivityStartTrigger(0.0), self)
      goto(Initialized) replying completed(triggerId,schedule[ActivityStartTrigger](0.0,self))
  }
  chainedWhen(Initialized) {
    case Event(TriggerWithId(ActivityStartTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val currentAct = currentActivity
      beamServices.agentSimEventsBus.publish(MatsimEvent(new ActivityStartEvent(tick, id, currentAct.getLinkId, currentAct.getFacilityId, currentAct.getType)))
      // Since this is the first activity of the day, we don't increment the currentActivityIndex
      logInfo(s"starting at ${currentAct.getType} @ $tick")
      goto(PerformingActivity) using info replying completed(triggerId, schedule[ActivityEndTrigger](currentAct.getEndTime, self))
  }
  chainedWhen(PerformingActivity) {
    case Event(TriggerWithId(ActivityEndTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val currentAct = currentActivity
      beamServices.agentSimEventsBus.publish(MatsimEvent(new ActivityEndEvent(tick, id, currentAct.getLinkId, currentAct.getFacilityId, currentAct.getType)))

      nextActivity.fold(
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
      processNextLegOrStartActivity(triggerId, tick)
    /*
     * Complete leg(s) as driver
     */
    case Event(TriggerWithId(CompleteDrivingMissionTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      processNextLegOrStartActivity(triggerId, tick)
    /*
     * Learn as passenger that leg is starting
     */
    case Event(NotifyLegStart(tick), info: BeamAgentInfo[PersonData]) =>
      val processedDataOpt = breakTripIntoNextLegAndRestOfTrip(_currentRoute, tick)
      processedDataOpt match {
        case Some(processedData) =>
          beamServices.vehicleRefs(processedData.nextLeg.beamVehicleId) ! EnterVehicle(tick, _currentVehicle.nestedVehicles.head)
          _currentRoute = processedData.restTrip
          _currentVehicle = _currentVehicle.push(processedData.nextLeg.beamVehicleId)
          goto(Moving)
        case None =>
          logError(s"Expected a non-empty BeamTrip but found ${_currentRoute}")
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
      val processedDataOpt = breakTripIntoNextLegAndRestOfTrip(_currentRoute, tick)
      processedDataOpt match {
        case Some(processedData) => // There are more legs in the trip...
          if(processedData.nextLeg.beamVehicleId == _currentVehicle.outermostVehicle()){
            // The next vehicle is the same as current so we do nothing
            stay()
          }else{
            // The next vehicle is different from current so we exit the current vehicle
            beamServices.vehicleRefs(_currentVehicle.outermostVehicle()) ! ExitVehicle(tick, _currentVehicle.penultimateVehicle())

            // Note that this will send a scheduling reply to a driver, not the scheduler, the driver must pass on the new trigger
            processNextLegOrStartActivity(-1L,tick)
          }
        case None =>
          logError(s"Expected a non-empty BeamTrip but found ${_currentRoute}")
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
  def processNextLegOrStartActivity(triggerId: Long, tick: Double): PersonAgent.this.State = {
    if(_currentRoute.legs.size > 0){
      val processedDataOpt = breakTripIntoNextLegAndRestOfTrip(_currentRoute, tick)
      processedDataOpt match {
        case Some(processedData) =>
          if(processedData.nextLeg.asDriver){
            val passengerSchedule = PassengerSchedule()
            val vehicleId = if(_humanBodyVehicle.isDefined && HumanBodyVehicle.isHumanBodyVehicle(processedData.nextLeg.beamVehicleId)){
              _humanBodyVehicle.get
            }else{
              processedData.nextLeg.beamVehicleId
            }
            passengerSchedule.addPassenger(vehicleId,Vector(processedData.nextLeg.beamLeg))
            beamServices.vehicleRefs(vehicleId) ! BecomeDriver(tick, id, Some(passengerSchedule))
            _currentRoute = processedData.restTrip
            stay() replying completed(triggerId,schedule[StartLegTrigger](processedData.nextLeg.beamLeg.startTime,self,processedData.nextLeg))
          }else{
            // We don't update PersonData with the rest of the currentRoute, this will happen when the agent recieves the NotifyStartLeg message
            goto(Waiting) replying completed(triggerId)
          }
        case None =>
          logError(s"Expected a non-empty BeamTrip but found ${_currentRoute}")
          goto(Error)
      }
    }else{
      nextActivity match {
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

  private def breakTripIntoNextLegAndRestOfTrip(trip: EmbodiedBeamTrip, tick: Double): Option[ProcessedData] = {
    if(trip.legs.size == 0){
      None
    }else{
      val nextLeg = trip.legs.head
      val restLegs = trip.legs.tail
      val restTrip: EmbodiedBeamTrip = EmbodiedBeamTrip(restLegs)
      val nextStart = if (restTrip.legs.nonEmpty) {
        restTrip.legs.head.beamLeg.startTime
      } else {
        tick
      }
      Some(ProcessedData(nextLeg, restTrip, nextStart))
    }
  }

  case class ProcessedData(nextLeg: EmbodiedBeamLeg, restTrip: EmbodiedBeamTrip, nextStart: Double)

  private def publishPathTraversal(event: PathTraversalEvent): Unit = {
    //TODO: convert pathTraversalEvents to hashset
    if (beamServices.beamConfig.beam.events.pathTraversalEvents contains event.beamLeg.mode.value.toLowerCase()) {
      beamServices.agentSimEventsBus.publish(MatsimEvent(event))
    }
  }

}


