package beam.metasim.agents

import beam.metasim.agents.AgentSpecialization.MobileAgent
import beam.metasim.agents.BeamAgent._
import beam.metasim.agents.PersonAgent._
import org.matsim.api.core.v01.population._
import org.matsim.api.core.v01.{Coord, Id}
import org.slf4j.LoggerFactory

import scala.reflect.ClassTag

/**
  * Created by sfeygin on 2/6/17.
  */
object PersonAgent {


  object PersonData {
    import scala.collection.JavaConverters._
    /**
      * `PersonData` factory method to assist in  creating `PersonData`
      *
      * @param plan : The plan having at least some `Activities`
      * @return `PersonData`
      */
    def apply(plan: Plan) = new PersonData(planToVec(plan), 0)

    def planToVec(plan: Plan): Vector[Activity] = {
      scala.collection.immutable.Vector.empty[Activity]++plan.getPlanElements.asScala.filter(p=>p.isInstanceOf[Activity]).map(p=>p.asInstanceOf[Activity])
    }
  }


  case class PersonData(activityChain: Vector[Activity], currentActivity: Int) extends BeamAgentData {
    val getCurrentActivity: Activity = {
      activityChain(currentActivity)
    }
    def inc: Int = currentActivity + 1
  }




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

  case class ActivityStartTrigger(override val triggerData: TriggerData) extends Trigger

  case class SelectRouteTrigger(override val triggerData: TriggerData) extends Trigger

  case class ActivityEndTrigger(override val triggerData: TriggerData) extends Trigger

}


// Agents initialized stateless w/out knowledge of their plan. This is sent to them by parent actor.
class PersonAgent(override val id: Id[PersonAgent], override val data: PersonData) extends BeamAgent[PersonData] with MobileAgent {

  private val logger = LoggerFactory.getLogger(classOf[PersonAgent])
  when(Initialized) {
    case Event(ActivityStartTrigger(newData), info: BeamAgentInfo[PersonData]) =>
      goto(PerformingActivity) using info.copy(id, PersonData(data.activityChain, 0)) replying CompletionNotice(newData)
  }

  when(PerformingActivity) {
    // DepartActivity trigger causes PersonAgent to initiate routing request from routing service
    case Event(ActivityEndTrigger(newData), info: BeamAgentInfo[PersonData]) =>
      goto(ChoosingMode) using info.copy(id, PersonData(info.data.activityChain, info.data.inc)) replying CompletionNotice(newData)
  }

  when(ChoosingMode) {
    case Event(SelectRouteTrigger(newData), info: BeamAgentInfo[PersonData]) => {
      stay()
    }
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
