package beam.metasim.playground.sid.agents

import akka.actor.FSM.Failure
import beam.metasim.agents.Ack
import beam.metasim.playground.sid.agents.BeamAgent._
import beam.metasim.playground.sid.akkaguice.NamedActor
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

  import beam.metasim.playground.sid.agents.PersonAgent._

  private val logger = LoggerFactory.getLogger(classOf[PersonAgent])


  when(PerformingActivity) {

    // DepartActivity trigger causes PersonAgent to initiate routing request from routing service
    case Event(DepartActivity(nextActivity), BeamAgentInfo(currentTask)) =>
      val fromActivity: Activity = currentTask.asInstanceOf[Activity]
      val toActivity: Activity = nextActivity.asInstanceOf[Activity]

      val fromFacilityId: Id[ActivityFacility] = Id.create(fromActivity.getType, classOf[ActivityFacility])
      val toFacilityId: Id[ActivityFacility] = Id.create(toActivity.getType, classOf[ActivityFacility])

      val fromFacilityLink: Link = network.getLinks.get(fromActivity.getLinkId)
      val toFacilityLink: Link = network.getLinks.get(toActivity.getLinkId)

      val fromFacility = createFacility(fromFacilityId, fromFacilityLink)
      val toFacility = createFacility(toFacilityId, toFacilityLink)

      val dummyId = Id.createPersonId(1) //TODO: get personId as constructor argument using Guice
      val dummyPerson: Person = population.createPerson(dummyId)

      val route = routerService.calcRoute(fromFacility, toFacility, toActivity.getEndTime, dummyPerson)
      if (route != null) {
        logger.warn("\n\n\t########## Dummy Route:" + route + "\n")
        context.parent ! Ack
      }else{
        context.parent ! Failure(s"$dummyId couldn't find route between $fromFacilityId and $toFacilityId. " +
          s"Staying at $fromActivity")
      }

      stay() using stateData.copy(currentTask = nextActivity.asInstanceOf[Activity])
  }

  onTransition {
    case Idle -> PerformingActivity => logger.debug("From init state to first activity")
    case PerformingActivity -> ChoosingMode => logger.debug("From activity to traveling")
    case ChoosingMode -> PerformingActivity => logger.debug("From traveling to activity")
  }


}
