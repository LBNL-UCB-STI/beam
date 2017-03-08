package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.Props
import beam.agentsim.agents.AgentSpecialization.MobileAgent
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.playground.sid.events.AgentsimEventsBus.MatsimEvent
import beam.agentsim.routing.DummyRouter.RoutingResponse
import beam.agentsim.routing.RoutingRequest
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


  case class PersonData(activityChain: Vector[Activity], currentActIx: Int) extends BeamAgentData {
    val getCurrentActivity: Activity = {
      activityChain(currentActIx)
    }

    val getNextActivity: Either[Activity, String] = {
      val nextActIx = currentActIx + 1
      if (activityChain.lengthCompare(nextActIx) > 0) Left(activityChain(nextActIx)) else Right("plan finished")
    }

    val getPrevActivity: Either[Activity, String] = {
      val prevActIx = currentActIx - 1
      if (activityChain.lengthCompare(prevActIx) < 0) Left(activityChain(prevActIx)) else Right("at start")
    }

    def inc: Int = currentActIx + 1

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

  case class ActivityStartTrigger(override val triggerData: TriggerData) extends Trigger[ActivityStartTrigger] {
    override def copy(newId: Int):ActivityStartTrigger  = ActivityStartTrigger(this.triggerData.copy(id = newId))
  }

  case class SelectRouteTrigger(override val triggerData: TriggerData) extends Trigger[SelectRouteTrigger] {
    override def copy(newId: Int) = SelectRouteTrigger(this.triggerData.copy(id = newId))
  }

  case class ActivityEndTrigger(override val triggerData: TriggerData) extends Trigger[ActivityEndTrigger] {
    override def copy(newId: Int) = ActivityEndTrigger(this.triggerData.copy(id = newId))
  }
  case class ApproachingDestinationTrigger(override val triggerData: TriggerData) extends Trigger[ApproachingDestinationTrigger]{
    override def copy(newId: Int) = ApproachingDestinationTrigger(this.triggerData.copy(id = newId))
  }

}


class PersonAgent(override val id: Id[PersonAgent], override val data: PersonData) extends BeamAgent[PersonData] with MobileAgent {

  import akka.util.Timeout
  import beam.agentsim.sim.AgentsimServices._

  private implicit val timeout = Timeout(5, TimeUnit.SECONDS)

  private val logger = LoggerFactory.getLogger(classOf[PersonAgent])
  when(Initialized) {
    case Event(ActivityStartTrigger(newData), info: BeamAgentInfo[PersonData]) =>
      goto(PerformingActivity) using info.copy(id, PersonData(data.activityChain, 0)) replying CompletionNotice(newData)
  }

  when(PerformingActivity) {
    // DepartActivity trigger causes PersonAgent to initiate routing request from routing service
    case Event(ActivityEndTrigger(newData), info: BeamAgentInfo[PersonData]) =>
      val msg = new ActivityEndEvent(newData.tick, Id.createPersonId(id), info.data.getCurrentActivity.getLinkId, info.data.getCurrentActivity.getFacilityId, info.data.getCurrentActivity.getType)
      agentSimEventsBus.publish(MatsimEvent(msg))

      goto(ChoosingMode) replying CompletionNotice(newData)
  }

  when(ChoosingMode) {
    case Event(SelectRouteTrigger(newData), info: BeamAgentInfo[PersonData]) =>
      // We would send a routing request here. We can simulate this for now.
      info.data.getNextActivity match {
        case Left(nextAct) => registry ! Registry.Tell("agent-router", RoutingRequest(info.data.getCurrentActivity, nextAct, newData.tick, id))
          stay() replying CompletionNotice(newData)
        case Right(done) => goto(Finished) replying CompletionNotice(newData)
      }

    case Event(RoutingResponse(legs), info: BeamAgentInfo[PersonData]) =>
      goto(Driving) using info.copy(id, PersonData(info.data.activityChain, info.data.inc))
  }


  when(Driving) {
    case Event(ApproachingDestinationTrigger(_), info: BeamAgentInfo[PersonData]) =>
      stay() using info

  }


  onTransition {
    case Uninitialized -> Initialized => logger.info("From uninitialized state to init state")
    case Initialized -> PerformingActivity => logger.info(s"From init state to ${data.getCurrentActivity.getType}")
    case PerformingActivity -> ChoosingMode => logger.info(s"From ${data.getCurrentActivity.getType} to mode choice")
    case ChoosingMode -> PerformingActivity => logger.info(s"From mode choice to ${data.getCurrentActivity.getType}")
  }

  override def getLocation: Coord = stateData.data.getCurrentActivity.getCoord

  override def hasVehicleAvailable(vehicleType: ClassTag[_]): Boolean = ???


}
