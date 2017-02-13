package beam.metasim.agents

import beam.metasim.agents.BeamAgent.{BeamAgentInfo, BeamState, Data, Idle}
import beam.metasim.agents.PersonAgent._
import com.google.inject.Inject
import com.google.inject.assistedinject.Assisted
import com.google.inject.name.Named
import org.matsim.api.core.v01.network.{Link, Network}
import org.matsim.api.core.v01.population._
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.mobsim.framework.PlanAgent
import org.matsim.core.router.RoutingModule
import org.matsim.facilities.{ActivityFacility, ActivityOption}
import org.slf4j.LoggerFactory

/**
  * Created by sfeygin on 2/6/17.
  */
object PersonAgent {

  trait PersonAgentFactory {
    def apply(id: Id[Person]): PersonAgent
  }

  case class PersonAgentInfo(previousPlanElement: PlanElement, currentPlanElement: PlanElement, nextPlanElement: PlanElement) extends Data


  trait InActivity extends BeamState {
    override def identifier = "In Activity"
  }

  case object PerformingActivity extends InActivity {
    override def identifier = "Performing an Activity"
  }

  trait Traveling extends BeamState

  case object ChoosingMode extends Traveling {
    override def identifier = "Choosing Travel Mode"
  }

  case object Walking extends Traveling {
    override def identifier = "Walking"
  }


  case object OnPublicTransit extends Traveling {
    override def identifier = "On Public Transit"
  }

  case class InitActivity(override val data: TriggerData, nextActivity: PlanElement) extends Trigger

  case class SelectRoute(override val data: TriggerData) extends Trigger

  case class DepartActivity(override val data: TriggerData, nextActivity: PlanElement) extends Trigger

  def createFacility(id: Id[ActivityFacility], link: Link): ActivityFacility = {
    if (link == null) throw new IllegalArgumentException("link == null")

    new ActivityFacility() {
      def getCoord: Coord = link.getFromNode.getCoord

      def getId: Id[ActivityFacility] = id

      def getCustomAttributes = throw new UnsupportedOperationException

      def getLinkId: Id[Link] = link.getId

      def addActivityOption(option: ActivityOption) { throw new UnsupportedOperationException }

      def getActivityOptions = throw new UnsupportedOperationException

      def setCoord(coord: Coord) { throw new RuntimeException("not implemented")
      }
    }

  }
}

class PersonAgent @Inject()(@Assisted personId: Id[Person], population: Population, network: Network, routerService: RoutingModule) extends BeamAgent(personId) with PlanAgent {

  private val logger = LoggerFactory.getLogger(classOf[PersonAgent])

  when(PerformingActivity) {
    // DepartActivity trigger causes PersonAgent to initiate routing request from routing service
    case Event(DepartActivity(data, nextActivity), info: PersonAgentInfo) =>
      val fromActivity: Activity = info.currentPlanElement.asInstanceOf[Activity]
      val toActivity: Activity = nextActivity.asInstanceOf[Activity]

      val fromFacilityId: Id[ActivityFacility] = Id.create(fromActivity.getType, classOf[ActivityFacility])
      val toFacilityId: Id[ActivityFacility] = Id.create(toActivity.getType, classOf[ActivityFacility])

      val fromFacilityLink: Link = network.getLinks.get(fromActivity.getLinkId)
      val toFacilityLink: Link = network.getLinks.get(toActivity.getLinkId)

      val fromFacility = PersonAgent.createFacility(fromFacilityId, fromFacilityLink)
      val toFacility = PersonAgent.createFacility(toFacilityId, toFacilityLink)

      val route = routerService.calcRoute(fromFacility, toFacility, toActivity.getEndTime, population.getPersons.get(personId))
      if (route != null) {
        logger.warn("\n\n\t########## Dummy Route:" + route + "\n")
        data.agent ! Ack
      } else {
        data.agent ! Failure(s"$personId couldn't find route between $fromFacilityId and $toFacilityId. " +
          s"Staying at $fromActivity")
      }

      stay() using info.copy(previousPlanElement = info.currentPlanElement, currentPlanElement = nextActivity.asInstanceOf[Activity])
  }

  when(ChoosingMode) {
    case Event(SelectRoute(data),info:PersonAgentInfo) =>{
      stay()
    }

  }


  onTransition {
    case Idle -> PerformingActivity => logger.debug("From init state to first activity")
    case PerformingActivity -> ChoosingMode => logger.debug("From activity to traveling")
    case ChoosingMode -> PerformingActivity => logger.debug("From traveling to activity")
  }

  override def getCurrentPlanElement: PlanElement = stateData.asInstanceOf[PersonAgentInfo].currentPlanElement

  override def getNextPlanElement: PlanElement = stateData.asInstanceOf[PersonAgentInfo].nextPlanElement

  override def getPreviousPlanElement: PlanElement = stateData.asInstanceOf[PersonAgentInfo].previousPlanElement

  override def getCurrentPlan: Plan = population.getPersons.get(personId).getSelectedPlan
}
