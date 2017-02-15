package beam.metasim.agents

import beam.metasim.agents.BeamAgent.Uninitialized
import beam.metasim.agents.PersonAgent._
import com.google.inject.Inject
import com.google.inject.assistedinject.Assisted
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.population._
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.facilities.{ActivityFacility, ActivityOption}
import org.slf4j.LoggerFactory

/**
  * Created by sfeygin on 2/6/17.
  */
object PersonAgent {
  import BeamAgent.BeamAgentState

  trait PersonAgentFactory {
    def apply(id: Id[Person]): PersonAgent
  }

  case class PersonAgentInfo(currentPlanElement: PlanElement) extends BeamAgent.Info


  trait InActivity extends BeamAgentState {
    override def identifier = "In Activity"
  }

  case object PerformingActivity extends InActivity {
    override def identifier = "Performing an Activity"
  }

  trait Traveling extends BeamAgentState

  case object ChoosingMode extends Traveling {
    override def identifier = "Choosing Travel Mode"
  }

  case object Walking extends Traveling {
    override def identifier = "Walking"
  }

  case object OnPublicTransit extends Traveling {
    override def identifier = "On Public Transit"
  }

  case class InitActivity(override val triggerData: TriggerData, nextActivity: PlanElement) extends Trigger

  case class SelectRoute(override val triggerData: TriggerData) extends Trigger

  case class DepartActivity(override val triggerData: TriggerData, nextActivity: PlanElement) extends Trigger

  def createFacility(id: Id[ActivityFacility], link: Link): ActivityFacility = {
    if (link == null) throw new IllegalArgumentException("link == null")

    new ActivityFacility() {
      def getCoord: Coord = link.getFromNode.getCoord

      def getId: Id[ActivityFacility] = id

      def getCustomAttributes = throw new UnsupportedOperationException

      def getLinkId: Id[Link] = link.getId

      def addActivityOption(option: ActivityOption) {
        throw new UnsupportedOperationException
      }

      def getActivityOptions = throw new UnsupportedOperationException

      def setCoord(coord: Coord) {
        throw new RuntimeException("not implemented")
      }
    }

  }
}

// Agents initialized stateless w/out knowledge of their plan. This is sent to them by parent actor.
class PersonAgent @Inject()(@Assisted personId: Id[Person]) extends BeamAgent(personId) {

  private val logger = LoggerFactory.getLogger(classOf[PersonAgent])

  when(PerformingActivity) {
    // DepartActivity trigger causes PersonAgent to initiate routing request from routing service
    case Event(DepartActivity(data, nextActivity), info: PersonAgentInfo) =>
      val fromActivity: Activity = info.currentPlanElement.asInstanceOf[Activity]
      val toActivity: Activity = nextActivity.asInstanceOf[Activity]
      val fromFacilityId: Id[ActivityFacility] = Id.create(fromActivity.getType, classOf[ActivityFacility])
      val toFacilityId: Id[ActivityFacility] = Id.create(toActivity.getType, classOf[ActivityFacility])
      sender() ! CompletionNotice(data)
      stay() using info.copy(currentPlanElement = nextActivity.asInstanceOf[Activity])
  }

  when(ChoosingMode) {
    case Event(SelectRoute(data), info: PersonAgentInfo) => {
      stay()
    }

  }


  onTransition {
    case Uninitialized -> PerformingActivity => logger.debug("From init state to first activity")
    case PerformingActivity -> ChoosingMode => logger.debug("From activity to traveling")
    case ChoosingMode -> PerformingActivity => logger.debug("From traveling to activity")
  }

}
