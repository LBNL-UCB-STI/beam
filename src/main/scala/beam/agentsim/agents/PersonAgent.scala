package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.Props
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.BeamAgentScheduler._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.events.AgentsimEventsBus.MatsimEvent
import beam.agentsim.routing.RoutingMessages.RoutingRequest
import beam.agentsim.routing.opentripplanner.OpenTripPlannerRouter.{BeamItinerary, BeamLeg, BeamTrip, RoutingResponse}
import beam.utils.DebugLib
import glokka.Registry
import org.matsim.api.core.v01.events._
import org.matsim.api.core.v01.population._
import org.matsim.api.core.v01.{Coord, Id, TransportMode}
import org.matsim.core.api.experimental.events.{AgentWaitingForPtEvent, TeleportationArrivalEvent}
import org.matsim.pt.transitSchedule.api.TransitStopFacility
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random

/**
  * Created by sfeygin on 2/6/17.
  */
object PersonAgent {

  val timeToChooseMode: Double = 30.0
  val minActDuration: Double = 1.0
  val teleportWalkDuration = 0.0

  private val logger = LoggerFactory.getLogger(classOf[PersonAgent])

  type ChoiceCalculator = (Vector[BeamTrip]) => BeamTrip

  def randomChoice(alternatives: Vector[BeamTrip]): BeamTrip = {
    Random.shuffle(alternatives.toList).head
  }

  // syntactic sugar for props creation
  def props(personId: Id[PersonAgent], personData: PersonData) = Props(new PersonAgent(personId, personData))

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
    def apply(plan: Plan) = new PersonData(planToVec(plan), 0, BeamTrip.noneTrip, Vector[BeamTrip](), randomChoice)

    def planToVec(plan: Plan): Vector[Activity] = {
      scala.collection.immutable.Vector.empty[Activity] ++ plan.getPlanElements.asScala.filter(p => p.isInstanceOf[Activity]).map(p => p.asInstanceOf[Activity])
    }

  }

  case class PersonData(activityChain: Vector[Activity], currentActivityIndex: Int = 0,
                        currentRoute: BeamTrip = BeamTrip.noneTrip,
                        currentAlternatives: Vector[BeamTrip] = Vector[BeamTrip](),
                        choiceCalculator: ChoiceCalculator) extends BeamAgentData {

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

  case class FinishWrapper(tick: Double, triggerId: Long) extends Trigger

  case class NextActivityWrapper(tick: Double, triggerId: Long) extends Trigger

  case class PersonDepartureTrigger(tick: Double) extends Trigger

  case class PersonEntersVehicleTrigger(tick: Double) extends Trigger

  case class PersonLeavesVehicleTrigger(tick: Double) extends Trigger

  case class PersonEntersBoardingQueueTrigger(tick: Double) extends Trigger

  case class PersonEntersAlightingQueueTrigger(tick: Double) extends Trigger

  case class PersonArrivesTransitStopTrigger(tick: Double) extends Trigger

  case class PersonArrivalTrigger(tick: Double) extends Trigger

  case class TeleportationArrivalTrigger(tick: Double) extends Trigger


}

class PersonAgent(override val id: Id[PersonAgent], override val data: PersonData) extends BeamAgent[PersonData] {

  import akka.pattern.{ask, pipe}
  import beam.agentsim.sim.AgentsimServices._

  private implicit val timeout = akka.util.Timeout(5000, TimeUnit.SECONDS)

  when(Uninitialized) {
    case Event(TriggerWithId(InitializeTrigger(tick), triggerId), _) =>
      goto(Initialized) replying CompletionNotice(triggerId)
  }

  when(Initialized) {
    case Event(TriggerWithId(ActivityStartTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val currentActivity = info.data.currentActivity
      agentSimEventsBus.publish(MatsimEvent(new ActivityStartEvent(tick, id, currentActivity.getLinkId, currentActivity.getFacilityId, currentActivity.getType)))
      // Since this is the first activity of the day, we don't increment the currentActivityIndex
      logInfo(s"starting at ${currentActivity.getType}")
      goto(PerformingActivity) using info replying completed(triggerId, schedule[ActivityEndTrigger](currentActivity.getEndTime))
  }

  when(PerformingActivity) {
    case Event(TriggerWithId(ActivityEndTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val currentActivity = info.data.currentActivity

      // Activity ends, so publish to EventBus
      agentSimEventsBus.publish(MatsimEvent(new ActivityEndEvent(tick, id, currentActivity.getLinkId, currentActivity.getFacilityId, currentActivity.getType)))

      info.data.nextActivity.fold(
        msg => {
          logInfo(s"didn't get nextActivity because $msg")
          self ! FinishWrapper(tick, triggerId)
        },
        nextAct => {
          logInfo(s"going to ${nextAct.getType}")
          val routerFuture = (beamRouter ? RoutingRequest(info.data.currentActivity, nextAct, tick, id)).mapTo[RoutingResponse] map { result =>
            val theRoute = result.els.getFirst.asInstanceOf[BeamItinerary].itinerary
            RouteResponseWrapper(tick, triggerId, theRoute)
          } pipeTo self
        }
      )
      stay()
    case Event(result: RouteResponseWrapper, info: BeamAgentInfo[PersonData]) =>
      val completionNotice = completed(result.triggerId, schedule[PersonDepartureTrigger](result.tick))
      if(info.id.toString.equals("3")){
        DebugLib.emptyFunctionForSettingBreakPoint();
      }
      // Send CN directly to scheduler.
      // Can't reply as usual here, since execution context post-pipe captures self as sender via closure.
      schedulerRef ! completionNotice
      goto(ChoosingMode) using stateData.copy(id, info.data.copy(currentAlternatives = result.alternatives))

    case Event(msg: FinishWrapper, info: BeamAgentInfo[PersonData]) =>
      schedulerRef ! CompletionNotice(msg.triggerId)
      goto(Error)
  }

  // TODO: Deal with case of arriving too late at next activity
  when(ChoosingMode) {
    case Event(TriggerWithId(PersonDepartureTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      if(info.data.currentAlternatives.size==0){
        goto(Finished) replying CompletionNotice(triggerId)
      }else {
        val tripChoice: BeamTrip = info.data.choiceCalculator(info.data.currentAlternatives)
        val procData = procStateData(tripChoice, tick)
        // Here, we actually need to do an extra step of look-ahead to get the correct (non-walk) mode
        val restTrip = procData.restTrip
        if(restTrip.legs.size>1) {
          restTrip.legs.head.mode match {
            case "WALK" =>
              agentSimEventsBus.publish(MatsimEvent(new PersonDepartureEvent(tick, id, info.data.currentActivity.getLinkId, TransportMode.walk)))
              goto(Walking) using BeamAgentInfo(id, stateData.data.copy(currentRoute = tripChoice)) replying
                completed(triggerId, schedule[PersonArrivalTrigger](tick + timeToChooseMode))
            case "CAR" =>
              agentSimEventsBus.publish(MatsimEvent(new PersonDepartureEvent(tick, id, info.data.currentActivity.getLinkId, TransportMode.car)))
              goto(Walking) using BeamAgentInfo(id, stateData.data.copy(currentRoute = tripChoice)) replying
                completed(triggerId, schedule[PersonEntersVehicleTrigger](tick + timeToChooseMode))
            case "WAITING" =>
              agentSimEventsBus.publish(MatsimEvent(new PersonDepartureEvent(tick, id, info.data.currentActivity.getLinkId, TransportMode.pt)))
              goto(Walking) using BeamAgentInfo(id, stateData.data.copy(currentRoute = tripChoice)) replying
                completed(triggerId, schedule[PersonArrivesTransitStopTrigger](tick + timeToChooseMode))
            case _ =>
              goto(Uninitialized) using stateData.copy(id, stateData.data.copy())
          }
        }else{
          agentSimEventsBus.publish(MatsimEvent(new PersonDepartureEvent(tick, id, info.data.currentActivity.getLinkId, TransportMode.walk)))
          goto(Walking) using BeamAgentInfo(id, stateData.data.copy(currentRoute = tripChoice)) replying
            completed(triggerId, schedule[PersonArrivalTrigger](tick + timeToChooseMode))
        }
      }
  }

  // TODO: Get Vehicle ids and implement currentVehicle as member of PersonData
  when(Walking) {
    // -> Driving
    case Event(TriggerWithId(PersonEntersVehicleTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val procData = procStateData(info.data.currentRoute, tick)
      agentSimEventsBus.publish(MatsimEvent(new PersonEntersVehicleEvent(tick, id, Id.createVehicleId(s"car_$id"))))
//      agentSimEventsBus.publish(MatsimEvent(new VehicleEntersTrafficEvent(tick, id, Id.createLinkId(procData.nextLeg.graphPath.value.head),Id.createVehicleId(s"car_$id"), TransportMode.car, 0.0)))
      goto(Driving) using BeamAgentInfo(id, stateData.data.copy(currentRoute = procData.restTrip)) replying
        completed(triggerId, schedule[PersonLeavesVehicleTrigger](procData.nextStart))

    // -> Transit
    case Event(TriggerWithId(PersonArrivesTransitStopTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val procData = procStateData(info.data.currentRoute, tick)
      goto(Waiting) using stateData.copy(id, info.data.copy(currentRoute = procData.restTrip)) replying
        completed(triggerId, schedule[PersonEntersBoardingQueueTrigger](procData.nextStart))

    // TODO: Transfer on Transit

    //-> NextActivity
    case Event(TriggerWithId(TeleportationArrivalTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val arrivalTime = teleportWalkDuration + tick
      agentSimEventsBus.publish(MatsimEvent(new TeleportationArrivalEvent(arrivalTime, id, 0)))
      val nextAct = info.data.nextActivity.right.get // No danger of failure here
      agentSimEventsBus.publish(MatsimEvent(new ActivityStartEvent(arrivalTime, id, nextAct.getLinkId, nextAct.getFacilityId, nextAct.getType)))
      logInfo(s"arrived at ${nextAct.getType} at $arrivalTime")
      // Agent should arrive before next activity ends, schedule trigger accordingly
      val actEndTriggerTime = Math.max(tick + minActDuration, nextAct.getEndTime)
      schedulerRef ! completed(triggerId, schedule[ActivityEndTrigger](actEndTriggerTime))
      goto(PerformingActivity) using stateData.copy(id, info.data.copy(currentActivityIndex = info.data.currentActivityIndex + 1))
  }

  // Driving-related states

  when(Driving) {
    case Event(TriggerWithId(PersonLeavesVehicleTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val procData = procStateData(info.data.currentRoute, tick)
      agentSimEventsBus.publish(MatsimEvent(new PersonLeavesVehicleEvent(procData.nextStart, id, Id.createVehicleId(s"car_$id"))))
      agentSimEventsBus.publish(MatsimEvent(new PersonArrivalEvent(procData.nextStart, id, info.data.nextActivity.right.get.getLinkId, TransportMode.car)))
      goto(Walking) using BeamAgentInfo(id, stateData.data.copy(currentRoute = procData.restTrip)) replying
        completed(triggerId, schedule[TeleportationArrivalTrigger](procData.nextStart))
  }

  // Transit-related states

  when(Waiting) {
    case Event(TriggerWithId(PersonEntersBoardingQueueTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val procData = procStateData(info.data.currentRoute, tick)
      agentSimEventsBus.publish(MatsimEvent(new AgentWaitingForPtEvent(tick, id, Id.create(Random.nextInt(),classOf[TransitStopFacility]),Id.create(Random.nextInt(),classOf[TransitStopFacility]))))
      goto(Boarding) using stateData.copy(id, info.data.copy(currentRoute = procData.restTrip)) replying
        completed(triggerId, schedule[PersonArrivesTransitStopTrigger](procData.nextStart))
  }

  when(Boarding) {
    case Event(TriggerWithId(PersonArrivesTransitStopTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val procData = procStateData(info.data.currentRoute, tick)
      agentSimEventsBus.publish(MatsimEvent(new PersonEntersVehicleEvent(tick, id, Id.createVehicleId(s"pt_$id"))))
      goto(OnTransit) using stateData.copy(id, info.data.copy(currentRoute = procData.restTrip)) replying
        completed(triggerId, schedule[PersonEntersAlightingQueueTrigger](procData.nextStart))
  }

  when(OnTransit) {
    case Event(TriggerWithId(PersonEntersAlightingQueueTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val procData = procStateData(info.data.currentRoute, tick)

      goto(Alighting) using stateData.copy(id, info.data.copy(currentRoute = procData.restTrip)) replying
        completed(triggerId, schedule[PersonLeavesVehicleTrigger](tick))
  }

  when(Alighting) {
    case Event(TriggerWithId(PersonLeavesVehicleTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val procData = procStateData(info.data.currentRoute, tick)
      agentSimEventsBus.publish(MatsimEvent(new PersonLeavesVehicleEvent(tick, id, Id.createVehicleId(s"pt_$id"))))

      val restTrip = procData.restTrip

      // If there are remaining legs in transit trip (Transfers)
      restTrip.legs.length match {
        case 1 =>
          agentSimEventsBus.publish(MatsimEvent(new PersonArrivalEvent(tick, id, info.data.nextActivity.right.get.getLinkId, TransportMode.pt)))
          goto(Walking) using stateData.copy(id, info.data.copy(currentRoute = restTrip)) replying
            completed(triggerId, schedule[TeleportationArrivalTrigger](procData.nextStart))
        case _ =>
          if(restTrip.legs.head.mode=="WALK"){ // walk to different stop
            goto(Walking) using BeamAgentInfo(id, stateData.data.copy(currentRoute = restTrip)) replying
              completed(triggerId, schedule[PersonArrivesTransitStopTrigger](procData.nextStart))
          }else{
            goto(Waiting) using BeamAgentInfo(id, stateData.data.copy(currentRoute = restTrip)) replying
              completed(triggerId, schedule[PersonEntersBoardingQueueTrigger](procData.nextStart))
          }
      }

  }


  onTransition {
    case Uninitialized -> Initialized =>
      registry ! Registry.Tell("scheduler", ScheduleTrigger(ActivityStartTrigger(0.0), self))
    case PerformingActivity -> ChoosingMode =>
      logInfo(s"going from PerformingActivity to ChoosingMode")
    case ChoosingMode -> Walking =>
      logInfo(s"going from ChoosingMode to Walking")
    case Walking -> Driving =>
      logInfo(s"going from Walking to Driving")
    case Driving -> Walking =>
      log.info(s"going from Driving to Walking")
    case Walking -> PerformingActivity =>
      logInfo(s"going from Walking to PerformingActivity")
  }

  /*
   * Helper methods
   */
  def logInfo(msg: String): Unit = {
    log.info(s"PersonAgent $id: $msg")
  }

  // NEVER use stateData in below, pass `info` object directly (closure around stateData on object creation)
  def currentLocation(info: BeamAgentInfo[PersonData]): Coord = {
    info.data.currentActivity.getCoord
  }

  // TODO: Use shapeless Hlist/Generics (if Triggers only have double field) or roll own method to accept multiple triggers.
  def schedule[T <: Trigger](tick: Double)(implicit tag: scala.reflect.ClassTag[T]): Vector[ScheduleTrigger] = {
    Vector[ScheduleTrigger](ScheduleTrigger(tag.runtimeClass.getConstructor(classOf[Double]).newInstance(new java.lang.Double(tick)).asInstanceOf[T], self))
  }

  def completed(triggerId: Long, scheduleTriggers: Vector[ScheduleTrigger]): CompletionNotice = {
    CompletionNotice(triggerId, scheduleTriggers)
  }

  private def procStateData(trip: BeamTrip, tick: Double): ProcessedData = {

    val nextLeg: BeamLeg = trip.legs.head
    val restTrip: BeamTrip = BeamTrip(trip.legs.tail)
    val nextStart = if(restTrip.legs.size>0){ restTrip.legs.head.startTime - nextLeg.startTime }else{ -999.0 }
    ProcessedData(nextLeg, restTrip, nextStart + tick)
  }

  case class ProcessedData(nextLeg: BeamLeg, restTrip: BeamTrip, nextStart: Double)


}
