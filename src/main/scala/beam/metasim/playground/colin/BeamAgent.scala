package beam.metasim.playground.colin

import akka.actor.FSM
import beam.metasim.agents.Ack
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import akka.persistence.fsm.PersistentFSM
import akka.persistence.fsm.PersistentFSM.FSMState
import scala.reflect.ClassTag
import scala.reflect.classTag
import akka.actor.Props
import org.matsim.api.core.v01.population.PlanElement

// states
sealed trait BeamState extends FSMState
case object InitialState extends BeamState {
  override def identifier: String = "InitState"
}
case object InActivity extends BeamState {
  override def identifier: String = "InActivity"
}
case object Traveling extends BeamState {
  override def identifier: String = "Traveling"
}

/**
  * Agent info consists of next MATSim plan element for agent to transition
  */
case class BeamAgentInfo(theData: Int)

sealed trait Trigger
case object Transition extends Trigger

sealed trait BeamDomainEvent
//  case class ExampleClass(item: Item) extends DomainEvent
final case class LabelActivity(newActivity: Int) extends BeamDomainEvent


/**
  * This FSM uses [[BeamState]] and [[BeamAgentInfo]] to define the state and
  * state data types.
  *
  * @param id create a new BeamAgent using the ID from the MATSim ID.
  */
class BeamAgent extends PersistentFSM[BeamState, BeamAgentInfo, BeamDomainEvent] {

  private val logger = LoggerFactory.getLogger(classOf[BeamAgent])

  startWith(InitialState, BeamAgentInfo(0))

  when(InitialState) {
    case Event(Transition, _) =>
      logger.info("in initial going to activity, data: none")
      goto(InActivity) applying LabelActivity(1)
  }
  when(InActivity) {
    case Event(Transition, prevLabel: BeamAgentInfo) =>
      logger.info("in activity and staying, data: " + prevLabel.theData)
      stay() applying LabelActivity(prevLabel.theData + 1)
  }

  onTransition {
    case InitialState -> InActivity => logger.debug("From init state to first activity")
    case InActivity -> Traveling => logger.debug("From activity to traveling")
    case Traveling -> InActivity => logger.debug("From traveling to activity")
  }
  def persistenceId: String = {
    "1"
  }
  def domainEventClassTag: ClassTag[BeamDomainEvent] = classTag[BeamDomainEvent]

  def applyEvent(domainEvent: BeamDomainEvent, currentData: BeamAgentInfo): BeamAgentInfo = {
    domainEvent match {
      case LabelActivity(newActivity: Int) ⇒ {
        logger.info("domainEvent with old data " + currentData.theData + " and new data " + newActivity)
        BeamAgentInfo(newActivity)
      }
    }
  }

}
