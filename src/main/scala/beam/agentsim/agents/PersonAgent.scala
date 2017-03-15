package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.Props
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.playground.sid.events.AgentsimEventsBus.MatsimEvent
import beam.agentsim.routing.DummyRouter.RoutingResponse
import beam.agentsim.routing.RoutingRequest
import beam.agentsim.agents.BeamAgentScheduler._
import glokka.Registry
import org.matsim.api.core.v01.events.ActivityEndEvent
import org.matsim.api.core.v01.population._
import org.matsim.api.core.v01.{Coord, Id}
import org.slf4j.LoggerFactory

import scala.reflect.ClassTag

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

  case class PersonData(activityChain: Vector[Activity], currentActivityIndex: Int, pendingTriggerId: Long = -1L) extends BeamAgentData {
    def activityOrMessage(ind: Int, msg: String): Either[String, Activity]= {
      if(ind < 0 || ind >= activityChain.length) Left(msg) else Right(activityChain(currentActivityIndex))
    }
    def currentOrNextActivity: Activity = {
      activityChain(currentActivityIndex)
    }
    def nextActivity: Either[String, Activity] = {
      activityOrMessage(currentActivityIndex + 1,"plan finished")
    }
    def prevActivity: Either[String, Activity] = {
      activityOrMessage(currentActivityIndex - 1,"at start")
    }
  }

  // End PersonData ~

  sealed trait InActivity extends BeamAgentState

  case object PerformingActivity extends InActivity {
    override def identifier = "Performing an Activity"
  }

  sealed trait Traveling extends BeamAgentState

  case object ChoosingMode extends Traveling {
    override def identifier = "Choosing travel mode"
  }

  case object Driving extends Traveling {
    override def identifier = "Driving"
  }

  case object Walking extends Traveling {
    override def identifier = "Walking"
  }

  case object OnPublicTransit extends Traveling {
    override def identifier = "On public transit"
  }

  case class ActivityStartTrigger(tick: Double) extends Trigger
  case class SelectRouteTrigger(tick: Double) extends Trigger
  case class ActivityEndTrigger(tick: Double) extends Trigger
  case class ApproachingDestinationTrigger(tick: Double) extends Trigger

}

class PersonAgent(override val id: Id[PersonAgent], override val data: PersonData) extends BeamAgent[PersonData] {

  import akka.util.Timeout
  import beam.agentsim.sim.AgentsimServices._

  private implicit val timeout = Timeout(5, TimeUnit.SECONDS)

  private val logger = LoggerFactory.getLogger(classOf[PersonAgent])

  when(Initialized) {
    case Event(TriggerWithId(ActivityStartTrigger(tick),triggerId), info: BeamAgentInfo[PersonData]) =>
      val currentActivity = info.data.currentOrNextActivity
      val msg = new ActivityEndEvent(tick, Id.createPersonId(id), currentActivity.getLinkId, currentActivity.getFacilityId, currentActivity.getType)
      agentSimEventsBus.publish(MatsimEvent(msg))
      goto(PerformingActivity) using info.copy(id, PersonData(data.activityChain, 0)) replying
        CompletionNotice(triggerId,Vector[ScheduleTrigger](ScheduleTrigger(ActivityEndTrigger(currentActivity.getEndTime),self)))
  }

  when(PerformingActivity) {
    case Event(TriggerWithId(ActivityEndTrigger(tick),triggerId), info: BeamAgentInfo[PersonData]) =>
      val currentActivity = info.data.currentOrNextActivity
      val msg = new ActivityEndEvent(tick, Id.createPersonId(id), currentActivity.getLinkId, currentActivity.getFacilityId, currentActivity.getType)
      agentSimEventsBus.publish(MatsimEvent(msg))
      goto(ChoosingMode) replying CompletionNotice(triggerId,Vector[ScheduleTrigger](ScheduleTrigger(SelectRouteTrigger(tick+30.0),self)))
  }

  when(ChoosingMode) {
    case Event(TriggerWithId(SelectRouteTrigger(tick),triggerId), info: BeamAgentInfo[PersonData]) =>
      info.data.nextActivity match {
        case Right(nextAct) =>
          val currentActivity = info.data.currentOrNextActivity
          registry ! Registry.Lookup("scheduler")
          registry ! Registry.Tell("agent-router", RoutingRequest(currentActivity, nextAct, tick, id))
          stay() using info.copy(id,info.data.copy(pendingTriggerId = triggerId))
        case Left(done) =>
          log.info("attempted to choose mode when no further activities exist in PersonAgent activity chain")
          goto(Finished) replying CompletionNotice(triggerId)
      }
    case Event(Registry.Found(actorName, actorRef), _) =>
      log.info(actorName)
      stay()
    case Event(Registry.NotFound(actorName), _) =>
      log.info(actorName)
      stay()

    case Event(RoutingResponse(routeItineraries), info: BeamAgentInfo[PersonData]) =>
      goto(Driving) using info.copy(id, PersonData(info.data.activityChain, info.data.currentActivityIndex)) replying(CompletionNotice(info.data.pendingTriggerId))
  }

  when(Driving) {
    case Event(TriggerWithId(ApproachingDestinationTrigger(tick),triggerId), info: BeamAgentInfo[PersonData]) =>
      stay() using info
  }

  onTransition {
    case Uninitialized -> Initialized =>
//      logger.info("From uninitialized state to init state")
      registry ! Registry.Tell("scheduler",ScheduleTrigger(ActivityStartTrigger(0.0),self))
//    case Initialized -> PerformingActivity => logger.info(s"From init state to ${data.getCurrentActivity.getType}")
//    case PerformingActivity -> ChoosingMode => logger.info(s"From ${data.getCurrentActivity.getType} to mode choice")
//    case ChoosingMode -> PerformingActivity => logger.info(s"From mode choice to ${data.getCurrentActivity.getType}")
  }

  def getLocation: Coord = {
    stateData.data.currentOrNextActivity.getCoord
  }

  def hasVehicleAvailable(vehicleType: ClassTag[_]): Boolean = ???


}
