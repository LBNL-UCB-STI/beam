package beam.metasim.agents

import beam.metasim.agents.BeamAgent.{BeamAgentInfo, BeamState, Uninitialized}
import beam.metasim.agents.PersonAgent.{ChoosingMode, DepartActivity, PerformingActivity}
import beam.metasim.akkaguice.NamedActor
import com.google.inject.Inject
import org.matsim.api.core.v01.network.{Link, Network}
import org.matsim.api.core.v01.population._
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.router.RoutingModule
import org.matsim.facilities.{ActivityFacility, ActivityOption}
import org.slf4j.LoggerFactory

/**
  * Created by sfeygin on 2/6/17.
  */
object PersonAgent extends NamedActor {
  override final val name: String = "PersonAgent"

  trait InActivity extends BeamState{
    override def identifier = "In Activity"
  }

  case object PerformingActivity extends InActivity {
    override def identifier = "Performing an Activity"
  }

  trait Traveling extends BeamState {
    override def identifier = "Traveling"
  }

  case object ChoosingMode extends Traveling {
    override def identifier = "Choosing Travel Mode"
  }

  case object Walking extends Traveling {
    override def identifier = "Walking"
  }

  case object Driving extends Traveling {
    override def identifier = "Driving"
  }

  case object OnPublicTransit extends Traveling {
    override def identifier = "On Public Transit"
  }

  case class InitActivity(override val data: TriggerData, nextActivity: PlanElement)  extends Trigger

  case class SelectRoute(override val data: TriggerData) extends Trigger

  case class DepartActivity(override val data:TriggerData, nextActivity: PlanElement) extends Trigger

  def createFacility(id: Id[ActivityFacility], link: Link): ActivityFacility = {
    if (link == null) throw new IllegalArgumentException("link == null")
    new ActivityFacility() {
      def getCoord: Coord = link.getFromNode.getCoord

      def getId: Id[ActivityFacility]
      =
        id

      def getCustomAttributes
      =
        throw new UnsupportedOperationException

      def getLinkId: Id[Link]
      =
        link.getId

      def addActivityOption(option: ActivityOption) {
        throw new UnsupportedOperationException
      }

      def getActivityOptions
      =
        throw new UnsupportedOperationException

      def setCoord(coord: Coord) {
        // TODO Auto-generated method stub
        throw new RuntimeException("not implemented")
      }
    }
  }
}

class PersonAgent @Inject()(population: PopulationFactory, network: Network, routerService: RoutingModule) extends BeamAgent {

  private val logger = LoggerFactory.getLogger(classOf[PersonAgent])


  when(PerformingActivity) {

    // DepartActivity trigger causes PersonAgent to initiate routing request from routing service
    case Event(DepartActivity(data, nextActivity), BeamAgentInfo(currentTask)) =>
      val fromActivity: Activity = currentTask.asInstanceOf[Activity]
      val toActivity: Activity = nextActivity.asInstanceOf[Activity]

      val fromFacilityId: Id[ActivityFacility] = Id.create(fromActivity.getType, classOf[ActivityFacility])
      val toFacilityId: Id[ActivityFacility] = Id.create(toActivity.getType, classOf[ActivityFacility])

      val fromFacilityLink: Link = network.getLinks.get(fromActivity.getLinkId)
      val toFacilityLink: Link = network.getLinks.get(toActivity.getLinkId)

      val fromFacility = PersonAgent.createFacility(fromFacilityId, fromFacilityLink)
      val toFacility = PersonAgent.createFacility(toFacilityId, toFacilityLink)

      val dummyId = Id.createPersonId(1) //TODO: get personId as constructor argument using Guice
      val dummyPerson: Person = population.createPerson(dummyId)

      val route = routerService.calcRoute(fromFacility, toFacility, toActivity.getEndTime, dummyPerson)
      if (route != null) {
        logger.warn("\n\n\t########## Dummy Route:" + route + "\n")
        data.agent ! Ack
      }else{
        data.agent ! Failure(s"$dummyId couldn't find route between $fromFacilityId and $toFacilityId. " +
          s"Staying at $fromActivity")
      }

      stay() using stateData.copy(currentTask = nextActivity.asInstanceOf[Activity])
  }

  onTransition {
    case Uninitialized -> PerformingActivity => logger.debug("From init state to first activity")
    case PerformingActivity -> ChoosingMode => logger.debug("From activity to traveling")
    case ChoosingMode -> PerformingActivity => logger.debug("From traveling to activity")
  }


}
