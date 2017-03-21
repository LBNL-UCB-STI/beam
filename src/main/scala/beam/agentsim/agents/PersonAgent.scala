package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.Props
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.BeamAgentScheduler._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.playground.sid.events.AgentsimEventsBus.MatsimEvent
import beam.agentsim.routing.RoutingRequest
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

  case class RouteReceivedPseudoTrigger(tick: Double, triggerId: Long, trip: BeamTrip) extends Trigger

  case class PersonDepartureTrigger(tick: Double) extends Trigger

  case class PersonEntersVehicleTrigger(tick: Double) extends Trigger

  case class PersonExitsVehicleTrigger(tick: Double) extends Trigger

  case class PersonEntersBoardingQueueTrigger(tick: Double) extends Trigger

  case class PersonEntersAlightingQueueTrigger(tick: Double) extends Trigger

  case class PersonArrivesTransitStopTrigger(tick: Double) extends Trigger

  case class PersonArrivalTrigger(tick: Double) extends Trigger

}

class PersonAgent(override val id: Id[PersonAgent], override val data: PersonData) extends BeamAgent[PersonData] {

  import akka.pattern.{ask,pipe}
  import beam.agentsim.sim.AgentsimServices._

  private implicit val timeout = akka.util.Timeout(5000, TimeUnit.SECONDS)

  private val logger = LoggerFactory.getLogger(classOf[PersonAgent])

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
      goto(PerformingActivity) using info replying CompletionNotice(triggerId, Vector[ScheduleTrigger](ScheduleTrigger(ActivityEndTrigger(currentActivity.getEndTime), self)))
  }

  when(PerformingActivity){
    case Event(TriggerWithId(ActivityEndTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>

      val currentActivity = info.data.currentActivity

      // Activity ends, so publish to EventBus
      val msg = new ActivityEndEvent(tick, Id.createPersonId(id), currentActivity.getLinkId, currentActivity.getFacilityId, currentActivity.getType)
      agentSimEventsBus.publish(MatsimEvent(msg))

      info.data.nextActivity.fold(
        msg => {
          logger.info(s"Didn't get nextActivity because $msg")
          goto(Finished) replying CompletionNotice(triggerId)
        },
        nextAct => {
          val routerFuture = (beamRouter ? RoutingRequest(info.data.currentActivity, nextAct, tick + timeToChooseMode, id)).mapTo[RoutingResponse] map { result =>
            val theRoute = result.els.getFirst.asInstanceOf[BeamItinerary].itinerary.head
            RouteReceivedPseudoTrigger(tick+timeToChooseMode,triggerId,theRoute)
          } pipeTo self
        }
      )
      log.info("after fold")
      stay()
    case Event(result: RouteReceivedPseudoTrigger, info: BeamAgentInfo[PersonData]) =>
      log.info("Received route")
      val departureTrigger = ScheduleTrigger(PersonDepartureTrigger(result.tick), self)
      val completionNotice = CompletionNotice(result.triggerId, Vector[ScheduleTrigger](departureTrigger))
      schedulerRef ! completionNotice  // Can't reply, since context is post-pipe.
      goto(ChoosingMode) using info.copy(id, info.data.copy(currentRoute = Some(result.trip)))
  }

  when(ChoosingMode) {
    case Event(TriggerWithId(PersonDepartureTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val enterVehicleTrigger: SchedulerMessage = ScheduleTrigger(PersonEntersVehicleTrigger(tick + timeToChooseMode), self)
      goto(Walking) using info
  }

  when(Walking) {
    case Event(TriggerWithId(PersonArrivesTransitStopTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      goto(Waiting) using info
    case Event(TriggerWithId(PersonEntersVehicleTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      goto(Driving) using info
    case Event(TriggerWithId(PersonArrivalTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      goto(PerformingActivity) using info
  }

  when(Driving) {
    case Event(TriggerWithId(PersonExitsVehicleTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      goto(Walking) using info
  }

  onTransition {
    case Uninitialized -> Initialized =>
      registry ! Registry.Tell("scheduler", ScheduleTrigger(ActivityStartTrigger(0.0), self))
    case PerformingActivity -> ChoosingMode =>
      log.info("FSM going from PerformingActivity to ChoosingMode")
    case ChoosingMode -> Walking=>
      log.info("FSM going from ChoosingMode to Walking")
  }

  /*
   * Helper methods
   */
  def currentLocation: Coord = {
    stateData.data.currentActivity.getCoord
  }

  val timeToChooseMode: Double = 30.0


}
