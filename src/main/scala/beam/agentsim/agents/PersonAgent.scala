package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, Props}
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent.{Driving, _}
import beam.agentsim.agents.TaxiAgent.DropOffCustomer
import beam.agentsim.agents.TaxiManager.{ReserveTaxi, ReserveTaxiConfirmation, TaxiInquiry, TaxiInquiryResponse}
import beam.agentsim.agents.modalBehaviors.ChoosesMode
import beam.agentsim.agents.modalBehaviors.ChoosesMode.{BeginModeChoiceTrigger, FinalizeModeChoiceTrigger}
import beam.agentsim.events.AgentsimEventsBus.MatsimEvent
import beam.agentsim.events.{PathTraversalEvent, PointProcessEvent}
import beam.agentsim.scheduler.BeamAgentScheduler._
import beam.agentsim.scheduler.{Trigger, TriggerWithId}
import beam.router.BeamRouter.{RoutingRequest, RoutingResponse}
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode._
import beam.router.RoutingModel.{BeamLeg, BeamTrip, DiscreteTime}
import beam.sim.{BeamServices, HasServices}
import glokka.Registry
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events._
import org.matsim.api.core.v01.population._
import org.matsim.core.api.experimental.events.AgentWaitingForPtEvent
import org.matsim.pt.transitSchedule.api.TransitStopFacility
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random

/**
  */
object PersonAgent {

  val timeToChooseMode: Double = 0.0
  val minActDuration: Double = 0.0
  val teleportWalkDuration = 0.0

  private val logger = LoggerFactory.getLogger(classOf[PersonAgent])

  // syntactic sugar for props creation
  def props(personId: Id[PersonAgent], personData: PersonData, services: BeamServices, behaviorsToMixIn: mutable.HashSet[Class[_]]) = {
    if(behaviorsToMixIn.contains(CanUseTaxi.getClass)){
      Props(new PersonAgent(personId, personData, services) with CanUseTaxi)
    }else{
      Props(new PersonAgent(personId, personData, services))
    }
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
    def apply(plan: Plan): PersonData = defaultPersonData(planToVec(plan))

    def apply(activities:Vector[Activity]):PersonData = defaultPersonData(activities)

    def planToVec(plan: Plan): Vector[Activity] = {
      scala.collection.immutable.Vector.empty[Activity] ++ plan.getPlanElements.asScala.filter(p => p.isInstanceOf[Activity]).map(p => p.asInstanceOf[Activity])
    }

    implicit def plan2PersonData(plan: Plan): PersonData = PersonData(plan)

    def defaultPersonData(vector: Vector[Activity]):PersonData = {
      PersonData(vector, 0, BeamTrip.noneTrip, Vector[BeamTrip](),  None)
    }

  }

  case class PersonData(activityChain: Vector[Activity], currentActivityIndex: Int = 0,
                        currentRoute: BeamTrip = BeamTrip.noneTrip,
                        currentAlternatives: Vector[BeamTrip] = Vector[BeamTrip](),
                        currentVehicle: Option[ActorRef]) extends BeamAgentData {

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

  case object Driving extends Traveling {
    override def identifier = "Driving"
  }

  case object InTaxi extends Traveling {
    override def identifier = "InTaxi"
  }

  case object Walking extends Traveling {
    override def identifier = "Walking"
  }

  case object Waiting extends Traveling {
    override def identifier = "Waiting"
  }

  case object OnTransit extends Traveling {
    override def identifier = "OnTransit"
  }

  case object Boarding extends Traveling {
    override def identifier = "Boarding"
  }

  case object Alighting extends Traveling {
    override def identifier = "Alighting"
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

  case class PersonEntersVehicleTrigger(tick: Double) extends Trigger

  case class PersonLeavesVehicleTrigger(tick: Double) extends Trigger

  case class PersonEntersTaxiTrigger(tick: Double) extends Trigger

  case class PersonLeavesTaxiTrigger(tick: Double) extends Trigger

  case class PersonEntersBoardingQueueTrigger(tick: Double) extends Trigger

  case class PersonEntersAlightingQueueTrigger(tick: Double) extends Trigger

  case class PersonArrivesTransitStopTrigger(tick: Double) extends Trigger

  case class PersonArrivalTrigger(tick: Double) extends Trigger

  case class TeleportationArrivalTrigger(tick: Double) extends Trigger


}

class PersonAgent(override val id: Id[PersonAgent], override val data: PersonData, val beamServices: BeamServices) extends BeamAgent[PersonData] with
  TriggerShortcuts with HasServices with CanUseTaxi with ChoosesMode {

  override var services: BeamServices = beamServices

  import akka.pattern.{ask, pipe}

//  var behaviors: Map[BeamAgentState,StateFunction] = registerBehaviors(Map[BeamAgentState,StateFunction](
//    Uninitialized ->
//    Initialized -> {
//      case Event(TriggerWithId(ActivityStartTrigger(tick), triggerId), info: BeamAgentData[PersonData]) =>
//        val currentActivity = info.data.currentActivity
//        services.agentSimEventsBus.publish(MatsimEvent(new ActivityStartEvent(tick, id, currentActivity.getLinkId, currentActivity.getFacilityId, currentActivity.getType)))
//        // Since this is the first activity of the day, we don't increment the currentActivityIndex
//        logInfo(s"starting at ${currentActivity.getType}")
//        goto(PerformingActivity) using info replying completed(triggerId, schedule[ActivityEndTrigger](currentActivity.getEndTime,self))
//  }))
//  when(Uninitialized)(behaviors(Uninitialized))
//  when(Initialized)(behaviors(Initialized))
  when(PerformingActivity) {
    case ev@Event(_, _) =>
      handleEvent(stateName, ev)
  }
  when(ChoosingMode) {
    case ev@Event(_, _) =>
      handleEvent(stateName, ev)
    case _ =>
      logError("unrec")
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
      services.agentSimEventsBus.publish(MatsimEvent(new ActivityStartEvent(tick, id, currentActivity.getLinkId, currentActivity.getFacilityId, currentActivity.getType)))
      // Since this is the first activity of the day, we don't increment the currentActivityIndex
      logInfo(s"starting at ${currentActivity.getType} @ $tick")
      goto(PerformingActivity) using info replying completed(triggerId, schedule[ActivityEndTrigger](currentActivity.getEndTime, self))
  }
  chainedWhen(PerformingActivity) {
    case Event(TriggerWithId(ActivityEndTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val currentActivity = info.data.currentActivity
      services.agentSimEventsBus.publish(MatsimEvent(new ActivityEndEvent(tick, id, currentActivity.getLinkId, currentActivity.getFacilityId, currentActivity.getType)))

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
      services.schedulerRef ! CompletionNotice(msg.triggerId)
      logError("FinishWrapper recieved while in PerformingActivity")
      goto(Error)
  }

  // TODO: Deal with case of arriving too late at next activity
//    case Event(TriggerWithId(PersonDepartureTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
//      if (info.data.currentAlternatives.isEmpty) {
//        logError("going to Error b/c empty route received")
//        goto(Error) replying CompletionNotice(triggerId)
//      } else {
//        val tripChoice: BeamTrip = info.data.choiceCalculator(info.data.currentAlternatives)
//        val procData = procStateData(tripChoice, tick)
//        // Here, we actually need to do an extra step of look-ahead to get the correct (non-walk) mode
//        val restTrip = procData.restTrip
//        restTrip.legs.headOption match {
//          case Some(BeamLeg(_, WALK, _, _)) | Some(BeamLeg(_, CAR, _, _)) | Some(BeamLeg(_, WAITING, _, _)) =>
//            services.agentSimEventsBus.publish(MatsimEvent(new PointProcessEvent(procData.nextLeg.startTime, id, PointProcessEvent.PointProcessType.Choice,
//              info.data.currentActivity.getCoord, tripChoice.choiceUtility)))
//          case _ =>
//          //do nothing
//        }
//        restTrip.legs.headOption match {
//          case Some(BeamLeg(_, WALK, _, _)) if restTrip.legs.length == 1 =>
//            services.agentSimEventsBus.publish(MatsimEvent(new PersonDepartureEvent(tick, id, info.data.currentActivity.getLinkId, WALK.matsimMode)))
//            goto(Walking) using BeamAgentInfo(id, stateData.data.copy(currentRoute = tripChoice)) replying
//              completed(triggerId, schedule[TeleportationArrivalTrigger](tick + timeToChooseMode,self))
//          case Some(BeamLeg(_, WALK, _, _)) if restTrip.legs.length > 1 =>
//            services.agentSimEventsBus.publish(MatsimEvent(new PersonDepartureEvent(tick, id, info.data.currentActivity.getLinkId, WALK.matsimMode)))
//            goto(Walking) using BeamAgentInfo(id, stateData.data.copy(currentRoute = tripChoice)) replying
//              completed(triggerId, schedule[TeleportationArrivalTrigger](tick + timeToChooseMode,self))
//          case Some(BeamLeg(_, CAR, _, _)) if restTrip.legs.length > 1 =>
//            services.agentSimEventsBus.publish(MatsimEvent(new PersonDepartureEvent(tick, id, info.data.currentActivity.getLinkId, CAR.matsimMode)))
//            goto(Walking) using BeamAgentInfo(id, stateData.data.copy(currentRoute = tripChoice)) replying
//              completed(triggerId, schedule[PersonEntersVehicleTrigger](tick + timeToChooseMode,self))
//          case Some(BeamLeg(_, TAXI, _, _)) if restTrip.legs.length > 1 =>
//            (services.taxiManager ? ReserveTaxi(info.data.currentActivity.getCoord)).mapTo[ReserveTaxiConfirmation] map { result =>
//              ReserveTaxiResponseWrapper(tick, triggerId, result.taxi, result.timeToCustomer, tripChoice)
//            } pipeTo self
//            stay()
//          case Some(BeamLeg(_, WAITING, _, _)) =>
//            services.agentSimEventsBus.publish(MatsimEvent(new PersonDepartureEvent(tick, id, info.data.currentActivity.getLinkId, TRANSIT.matsimMode)))
//            goto(Walking) using BeamAgentInfo(id, stateData.data.copy(currentRoute = tripChoice)) replying
//              completed(triggerId, schedule[PersonArrivesTransitStopTrigger](tick + timeToChooseMode,self))
//          case Some(BeamLeg(_, _, _, _)) =>
//            logError(s"going to Error on trigger $triggerId in ChoosingMode due to unknown mode")
//            goto(Error) using stateData.copy(id, stateData.data.copy()) replying CompletionNotice(triggerId)
//          case None | Some(_) =>
//            logError(s"going to Error on trigger $triggerId in ChoosingMode due to no next leg")
//            goto(Error) using stateData.copy(id, stateData.data.copy()) replying CompletionNotice(triggerId)
//        }
//      }
//    case Event(ReserveTaxiResponseWrapper(tick, triggerId, taxi, timeToCustomer, tripChoice), info: BeamAgentInfo[PersonData]) =>
//      taxi match {
//        case Some(theTaxi) =>
//          services.agentSimEventsBus.publish(MatsimEvent(new PersonDepartureEvent(tick, id, info.data.currentActivity.getLinkId, CAR.matsimMode)))
//          services.schedulerRef ! completed(triggerId, schedule[PersonEntersTaxiTrigger](tick + timeToCustomer, self))
//          goto(Walking) using BeamAgentInfo(id, info.data.copy(currentRoute = tripChoice, currentVehicle = taxi))
//        case None =>
//          logError(s"going to Error on trigger $triggerId in ChoosingMode due to no taxi")
//          services.schedulerRef ! CompletionNotice(triggerId)
//          goto(Error) using stateData.copy(id, stateData.data.copy())
//      }
//  }

  // TODO: Get Vehicle ids and implement currentVehicle as member of PersonData
  when(Walking) {
    // -> Driving
    case Event(TriggerWithId(PersonEntersVehicleTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val procData = procStateData(info.data.currentRoute, tick)
      services.agentSimEventsBus.publish(MatsimEvent(new PersonEntersVehicleEvent(tick, id, Id.createVehicleId(s"car_$id"))))
      goto(Driving) using BeamAgentInfo(id, stateData.data.copy(currentRoute = procData.restTrip)) replying
        completed(triggerId, schedule[PersonLeavesVehicleTrigger](procData.nextStart,self))

    // -> Taxi
    case Event(TriggerWithId(PersonEntersTaxiTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val procData = procStateData(info.data.currentRoute, tick)
      services.agentSimEventsBus.publish(MatsimEvent(new PersonEntersVehicleEvent(tick, id, Id.createVehicleId(s"taxi_$id"))))
      goto(InTaxi) using BeamAgentInfo(id, stateData.data.copy(currentRoute = procData.restTrip)) replying
        completed(triggerId, schedule[PersonLeavesTaxiTrigger](procData.nextStart,self))

    // -> Transit
    case Event(TriggerWithId(PersonArrivesTransitStopTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val procData = procStateData(info.data.currentRoute, tick)
      services.agentSimEventsBus.publish(MatsimEvent(PathTraversalEvent(id, procData.nextLeg)))
      goto(Waiting) using stateData.copy(id, info.data.copy(currentRoute = procData.restTrip)) replying
        completed(triggerId, schedule[PersonEntersBoardingQueueTrigger](procData.nextStart,self))

    // TODO: Transfer on Transit

    //-> NextActivity
    case Event(TriggerWithId(TeleportationArrivalTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val procData = procStateData(info.data.currentRoute, tick)
      services.agentSimEventsBus.publish(MatsimEvent(PathTraversalEvent(id, procData.nextLeg)))
      val arrivalTime = teleportWalkDuration + tick
      services.agentSimEventsBus.publish(MatsimEvent(new PersonArrivalEvent(arrivalTime, id, info.data.nextActivity.right.get.getLinkId, WALK.matsimMode)))
      val nextAct = info.data.nextActivity.right.get // No danger of failure here
      services.agentSimEventsBus.publish(MatsimEvent(new ActivityStartEvent(arrivalTime, id, nextAct.getLinkId, nextAct.getFacilityId, nextAct.getType)))
      logInfo(s"arrived at ${nextAct.getType} at $arrivalTime")
      // Agent should arrive before next activity ends, schedule trigger accordingly
      val actEndTriggerTime = Math.max(tick + minActDuration, nextAct.getEndTime)
      services.schedulerRef ! completed(triggerId, schedule[ActivityEndTrigger](actEndTriggerTime, self))
      goto(PerformingActivity) using stateData.copy(id, info.data.copy(currentActivityIndex = info.data.currentActivityIndex + 1))
  }

  // Driving-related states
  when(Driving) {
    case Event(TriggerWithId(PersonLeavesVehicleTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val procData = procStateData(info.data.currentRoute, tick)
      publishPathTraversal(PathTraversalEvent(id, procData.nextLeg))
      services.agentSimEventsBus.publish(MatsimEvent(new PersonLeavesVehicleEvent(procData.nextStart, id, Id.createVehicleId(s"car_$id"))))
      services.agentSimEventsBus.publish(MatsimEvent(new PersonArrivalEvent(procData.nextStart, id, info.data.nextActivity.right.get.getLinkId, CAR.matsimMode)))
      goto(Walking) using BeamAgentInfo(id, stateData.data.copy(currentRoute = procData.restTrip)) replying
        completed(triggerId, schedule[TeleportationArrivalTrigger](procData.nextStart,self))
  }

  // Taxi-related states
  when(InTaxi) {
    case Event(TriggerWithId(PersonLeavesTaxiTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val procData = procStateData(info.data.currentRoute, tick)
      publishPathTraversal(PathTraversalEvent(id, procData.nextLeg))
      services.agentSimEventsBus.publish(MatsimEvent(new PersonLeavesVehicleEvent(procData.nextStart, id, Id.createVehicleId(s"car_$id"))))
      services.agentSimEventsBus.publish(MatsimEvent(new PersonArrivalEvent(procData.nextStart, id, info.data.nextActivity.right.get.getLinkId, CAR.matsimMode)))
      procData.nextLeg.travelPath.swap.foreach(_.latLons.headOption.foreach(info.data.currentVehicle.get ! DropOffCustomer(_)))
      goto(Walking) using BeamAgentInfo(id, stateData.data.copy(currentRoute = procData.restTrip)) replying
        completed(triggerId, schedule[TeleportationArrivalTrigger](procData.nextStart,self))
  }

  // Transit-related states
  when(Waiting) {
    case Event(TriggerWithId(PersonEntersBoardingQueueTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val procData = procStateData(info.data.currentRoute, tick)
      publishPathTraversal(PathTraversalEvent(id, procData.nextLeg))
      services.agentSimEventsBus.publish(MatsimEvent(new AgentWaitingForPtEvent(tick, id, Id.create(Random.nextInt(), classOf[TransitStopFacility]), Id.create(Random.nextInt(), classOf[TransitStopFacility]))))
      goto(Boarding) using stateData.copy(id, info.data.copy(currentRoute = procData.restTrip)) replying
        completed(triggerId, schedule[PersonArrivesTransitStopTrigger](procData.nextStart,self))
  }

  when(Boarding) {
    case Event(TriggerWithId(PersonArrivesTransitStopTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val procData = procStateData(info.data.currentRoute, tick)
      publishPathTraversal(PathTraversalEvent(id, procData.nextLeg))
      services.agentSimEventsBus.publish(MatsimEvent(new PersonEntersVehicleEvent(tick, id, Id.createVehicleId(s"pt_$id"))))
      goto(OnTransit) using stateData.copy(id, info.data.copy(currentRoute = procData.restTrip)) replying
        completed(triggerId, schedule[PersonEntersAlightingQueueTrigger](procData.nextStart,self))
  }

  when(OnTransit) {
    case Event(TriggerWithId(PersonEntersAlightingQueueTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val procData = procStateData(info.data.currentRoute, tick)
      publishPathTraversal(PathTraversalEvent(id, procData.nextLeg))
      goto(Alighting) using stateData.copy(id, info.data.copy(currentRoute = procData.restTrip)) replying
        completed(triggerId, schedule[PersonLeavesVehicleTrigger](procData.nextStart,self))
  }

//  when(Alighting) {
//    case Event(TriggerWithId(PersonLeavesVehicleTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
//      val procData = procStateData(info.data.currentRoute, tick)
//      services.agentSimEventsBus.publish(MatsimEvent(new PersonLeavesVehicleEvent(tick, id, Id.createVehicleId(s"pt_$id"))))
//      publishPathTraversal(PathTraversalEvent(id, procData.nextLeg))
//      val restTrip = procData.restTrip
//
//      // If there are remaining legs in transit trip (Transfers)
//      restTrip.legs.headOption match {
//        case Some(BeamLeg(_, WALK, _, _)) if restTrip.legs.length == 1 =>
//          services.agentSimEventsBus.publish(MatsimEvent(new PersonArrivalEvent(tick, id, info.data.nextActivity.right.get.getLinkId, TRANSIT.matsimMode)))
//          goto(Walking) using stateData.copy(id, info.data.copy(currentRoute = restTrip)) replying
//            completed(triggerId, schedule[TeleportationArrivalTrigger](procData.nextStart,self))
//        case Some(BeamLeg(_, WALK, _, _)) if restTrip.legs.length > 1 =>
//          goto(Walking) using BeamAgentInfo(id, stateData.data.copy(currentRoute = restTrip)) replying // walk to different stop
//            completed(triggerId, schedule[PersonArrivesTransitStopTrigger](procData.nextStart,self))
//        case Some(BeamLeg(_, WAITING, _, _)) =>
//          goto(Waiting) using BeamAgentInfo(id, stateData.data.copy(currentRoute = restTrip)) replying
//            completed(triggerId, schedule[PersonEntersBoardingQueueTrigger](procData.nextStart,self))
//        case Some(BeamLeg(_, _, _, _)) => // Not sure if this is a good idea
//          goto(Walking) using stateData.copy(id, info.data.copy(currentRoute = restTrip)) replying //
//            completed(triggerId, schedule[TeleportationArrivalTrigger](procData.nextStart,self))
//        case None =>
//          logError(s"going to Error on trigger $triggerId in ALIGHTING")
//          goto(Error) using stateData.copy(id, stateData.data.copy()) replying CompletionNotice(triggerId)
//      }
//  }


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

  /*
   * Helper methods
   */
  def logInfo(msg: String): Unit = {
    log.info(s"${logPrefix}$msg")
  }
  def logWarn(msg: String): Unit = {
    log.warning(s"${logPrefix}$msg")
  }
  def logError(msg: String): Unit = {
    log.error(s"${logPrefix}$msg")
  }
  def logPrefix(): String = s"PersonAgent $id: "

  // NEVER use stateData in below, pass `info` object directly (closure around stateData on object creation)

  private def procStateData(trip: BeamTrip, tick: Double): ProcessedData = {

    val nextLeg: BeamLeg = trip.legs.head
    val restTrip: BeamTrip = BeamTrip(trip.legs.tail)
    val nextStart = if (restTrip.legs.nonEmpty) {
      restTrip.legs.head.startTime
    } else {
      tick
    }
    ProcessedData(nextLeg, restTrip, nextStart)
  }

  case class ProcessedData(nextLeg: BeamLeg, restTrip: BeamTrip, nextStart: Double)

  private def publishPathTraversal(event: PathTraversalEvent): Unit = {
    //TODO: convert pathTraversalEvents to hashset
    if (services.beamConfig.beam.events.pathTraversalEvents contains event.beamLeg.mode.value.toLowerCase()) {
      services.agentSimEventsBus.publish(MatsimEvent(event))
    }
  }

}


