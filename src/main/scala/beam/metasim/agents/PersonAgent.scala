package beam.metasim.agents

import beam.metasim.agents.BeamAgent._
import beam.metasim.agents.PersonAgent._
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population._
import org.slf4j.LoggerFactory

/**
  * Created by sfeygin on 2/6/17.
  */
object PersonAgent {


  case class PersonAgentData(currentPlanElement: PlanElement) extends BeamAgentData

  trait InActivity extends BeamAgentState

  case object PerformingActivity extends InActivity {
    override def identifier = "Performing an Activity"
  }

  trait Traveling extends BeamAgentState

  case object ChoosingMode extends Traveling {
    override def identifier = "Choosing travel mode"
  }

  case object Driving extends Traveling{
    override def identifier = "Driving"
  }

  case object Walking extends Traveling {
    override def identifier = "Walking"
  }

  case object OnPublicTransit extends Traveling {
    override def identifier = "On public transit"
  }

  case class InitActivity(override val triggerData: TriggerData, nextActivity: PlanElement) extends Trigger

  case class SelectRoute(override val triggerData: TriggerData) extends Trigger

  case class DepartActivity(override val triggerData: TriggerData, nextActivity: Activity) extends Trigger

}


// Agents initialized stateless w/out knowledge of their plan. This is sent to them by parent actor.
class PersonAgent (override val id:Id[PersonAgent], override val data: PersonAgentData) extends BeamAgent[PersonAgentData] {


  private val logger = LoggerFactory.getLogger(classOf[PersonAgent])
  when(Initialized){
    case Event(DepartActivity(newData,nextActivity),info:BeamAgentInfo[PersonAgentData])=>
      sender() ! CompletionNotice(newData)
      goto(PerformingActivity) using info.copy(id, PersonAgentData(nextActivity))
  }

  when(PerformingActivity) {
    // DepartActivity trigger causes PersonAgent to initiate routing request from routing service
    case Event(DepartActivity(newData, nextActivity), info:BeamAgentInfo[PersonAgentData]) =>
      sender() ! CompletionNotice(newData)
      goto(ChoosingMode) using info.copy(id, PersonAgentData(nextActivity))
  }

  when(ChoosingMode) {
    case Event(SelectRoute(newData), info: BeamAgentInfo[PersonAgentData]) => {
      stay()
    }
  }


  onTransition {
    case Uninitialized -> PerformingActivity => logger.debug("From init state to first activity")
    case PerformingActivity -> ChoosingMode => logger.debug("From activity to traveling")
    case ChoosingMode -> PerformingActivity => logger.debug("From traveling to activity")
  }


}
