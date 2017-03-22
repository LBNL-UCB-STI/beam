package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.Props
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.BeamAgentScheduler._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.playground.sid.events.AgentsimEventsBus.MatsimEvent
import beam.agentsim.routing.RoutingMessages.RoutingRequest
import beam.agentsim.routing.opentripplanner.OpenTripPlannerRouter.{BeamItinerary, BeamTrip, RoutingResponse}
import glokka.Registry
import org.matsim.api.core.v01.events.ActivityEndEvent
import org.matsim.api.core.v01.population._
import org.matsim.api.core.v01.{Coord, Id}
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Created by sfeygin on 2/6/17.
  */
object PersonAgent {

  val timeToChooseMode: Double = 30.0

  private val logger = LoggerFactory.getLogger(classOf[PersonAgent])

  private implicit val timeout = akka.util.Timeout(5000, TimeUnit.SECONDS)



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
    def apply(plan: Plan) = new PersonData(planToVec(plan), 0)

    def planToVec(plan: Plan): Vector[Activity] = {
      scala.collection.immutable.Vector.empty[Activity] ++ plan.getPlanElements.asScala.filter(p => p.isInstanceOf[Activity]).map(p => p.asInstanceOf[Activity])
    }

  }

  case class PersonData(activityChain: Vector[Activity], currentActivityIndex: Int = 0, currentRoute: Option[BeamTrip] = None) extends BeamAgentData {
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

  case object Alighting extends Traveling {
    override def identifier = "Alighting"
  }

  case class ActivityStartTrigger(tick: Double) extends Trigger

  case class ActivityEndTrigger(tick: Double) extends Trigger

  case class RouteResponseWrapper(tick: Double, triggerId: Long, trip: BeamTrip) extends Trigger

  case class PersonDepartureTrigger(tick: Double) extends Trigger

  case class PersonEntersVehicleTrigger(tick: Double) extends Trigger

  case class PersonExitsVehicleTrigger(tick: Double) extends Trigger

  case class PersonEntersBoardingQueueTrigger(tick: Double) extends Trigger

  case class PersonEntersAlightingQueueTrigger(tick: Double) extends Trigger

  case class PersonArrivesTransitStopTrigger(tick: Double) extends Trigger

  case class PersonArrivalTrigger(tick: Double) extends Trigger

}

class PersonAgent(override val id: Id[PersonAgent], override val data: PersonData) extends BeamAgent[PersonData] {

  import akka.pattern.{ask, pipe}
  import beam.agentsim.sim.AgentsimServices._




  def routeReceived(data: BeamAgent.BeamAgentInfo[PersonData]): Boolean = {
    data match {
      case data: BeamAgent.BeamAgentInfo[PersonData] => data.data.currentRoute.nonEmpty
      case _ => false
    }
  }

  when(Initialized) {
    case Event(TriggerWithId(ActivityStartTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val currentActivity = info.data.currentActivity
      val msg = new ActivityEndEvent(tick, Id.createPersonId(id), currentActivity.getLinkId, currentActivity.getFacilityId, currentActivity.getType)
      agentSimEventsBus.publish(MatsimEvent(msg))
      // Since this is the first activity of the day, we don't increment the currentActivityIndex
      goto(PerformingActivity) using info replying completed(triggerId, schedule[ActivityEndTrigger](currentActivity.getEndTime))
  }

  when(PerformingActivity) {
    case Event(TriggerWithId(ActivityEndTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>

      val currentActivity = info.data.currentActivity

      // Activity ends, so publish to EventBus
      // FIXME: This isn't working... needs to be enforced by contract
      val msg = new ActivityEndEvent(tick, Id.createPersonId(id), currentActivity.getLinkId, currentActivity.getFacilityId, currentActivity.getType)
      agentSimEventsBus.publish(MatsimEvent(msg))

      info.data.nextActivity.fold(
        msg => {
          logInfo(s"didn't get nextActivity because $msg")
          goto(Finished) replying CompletionNotice(triggerId)
        },
        nextAct => {
          val routerFuture = (beamRouter ? RoutingRequest(info.data.currentActivity, nextAct, tick + timeToChooseMode, id)).mapTo[RoutingResponse] map { result =>
            val theRoute = result.els.getFirst.asInstanceOf[BeamItinerary].itinerary.head
            RouteResponseWrapper(tick + timeToChooseMode, triggerId, theRoute)
          } pipeTo self
        }
      )
      stay()
    case Event(result: RouteResponseWrapper, _)  =>
      logInfo(s"received route")
      goto(ChoosingMode) using updateRoute(result)
  }

  when(ChoosingMode) {
    case Event(TriggerWithId(PersonDepartureTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      goto(Walking) using info replying completed(triggerId, schedule[PersonEntersVehicleTrigger](tick + timeToChooseMode))
  }

  when(Walking) {
    case Event(TriggerWithId(PersonArrivesTransitStopTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      goto(Waiting) using info

    case Event(TriggerWithId(PersonEntersVehicleTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      goto(Driving) using info replying completed(triggerId, schedule[PersonExitsVehicleTrigger](tick + timeToChooseMode))

    case Event(TriggerWithId(PersonArrivalTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      goto(PerformingActivity) using nextActivity replying
        completed(triggerId, schedule[ActivityEndTrigger](info.data.nextActivity.right.get.getEndTime))
  }

  when(Driving) {
    case Event(TriggerWithId(PersonExitsVehicleTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      goto(Walking) using info replying completed(triggerId, schedule[PersonArrivalTrigger](tick + timeToChooseMode))
  }

  onTransition {
    case Uninitialized -> Initialized =>
      registry ! Registry.Tell("scheduler", ScheduleTrigger(ActivityStartTrigger(0.0), self))
    case PerformingActivity -> ChoosingMode =>
      logInfo(s"going from ${stateData.data.currentActivity.getType} to ChoosingMode")
    case ChoosingMode -> Walking =>
      logInfo(s"going from ChoosingMode to Walking having chosen ${stateData.data.currentRoute.getOrElse(BeamTrip.noneTrip).legs.head.mode}")
    case Walking -> Driving =>
      logInfo(s"going from Walking to Driving")
    case Driving -> Walking =>
      log.info(s"going from Driving to Walking")
    case Walking -> PerformingActivity =>
      logInfo(s"going from Walking to ${stateData.data.currentActivity.getType}")
  }

  /*
   * Helper methods
   */
  def logInfo(msg: String): Unit ={
    log.info(s"PersonAgent $id: $msg")
  }

  // TODO: This is fine for now, but consider +/- of passing stateData as param, creating pure and more portable static methods...
  def currentLocation: Coord = {
    stateData.data.currentActivity.getCoord
  }

  def nextActivity(): BeamAgentInfo[PersonData] = {
    stateData.copy(id, stateData.data.copy(activityChain = stateData.data.activityChain, currentActivityIndex = stateData.data.currentActivityIndex + 1, currentRoute = stateData.data.currentRoute))
  }

  // TODO: Use shapeless Hlist/Generics (if Triggers only have double field) or roll own method to accept multiple triggers.
  def schedule[T <: Trigger](tick: Double)(implicit tag: scala.reflect.ClassTag[T]): Vector[ScheduleTrigger] = {
    Vector[ScheduleTrigger](ScheduleTrigger(tag.runtimeClass.getConstructor(classOf[Double]).newInstance(new java.lang.Double(tick)).asInstanceOf[T], self))
  }

  def completed(triggerId: Long, scheduleTriggers: Vector[ScheduleTrigger]): CompletionNotice = {
    CompletionNotice(triggerId, scheduleTriggers)
  }

  def updateRoute(result: RouteResponseWrapper): BeamAgentInfo[PersonData]={
    val completionNotice = completed(result.triggerId, schedule[PersonDepartureTrigger](result.tick))
    // Send CN directly to scheduler.
    // Can't reply as usual here, since execution context post-pipe captures self as sender via closure.
    schedulerRef ! completionNotice
    stateData.copy(id, stateData.data.copy(currentRoute = Some(result.trip)))
  }




}
