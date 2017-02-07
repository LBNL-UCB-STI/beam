package beam.metasim.playground.sid.agents

import beam.metasim.agents.Ack
import beam.metasim.playground.sid.agents.BeamAgent.{ChoosingMode, Idle, Initialized, PerformingActivity}
import beam.metasim.playground.sid.akkaguice.NamedActor
import beam.metasim.playground.sid.usecases.RouterService
import com.google.inject.Inject
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.slf4j.LoggerFactory

/**
  * Created by sfeygin on 2/6/17.
  */
object PersonAgent extends NamedActor{
  override final val name: String = "PersonAgent"
}

class PersonAgent @Inject() (routerService:RouterService) extends BeamAgent{
  private val logger = LoggerFactory.getLogger(classOf[PersonAgent])

  when(Idle) {
    case Event(StartDay, _) =>
      context.parent ! Ack
      goto(PerformingActivity)
  }

  when(PerformingActivity) {
    case Event(DepartActivity, _) =>
      {
        logger.warn("\n\n\t########## Dummy Route:"+routerService.getRoute(Id.createLinkId(1),Id.createLinkId(2))+"\n")
        stay()
      }
  }

  onTransition {
    case Idle -> PerformingActivity => logger.debug("From init state to first activity")
    case PerformingActivity -> ChoosingMode => logger.debug("From activity to traveling")
    case ChoosingMode -> PerformingActivity => logger.debug("From traveling to activity")
  }
}
