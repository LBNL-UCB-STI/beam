package beam.metasim.playground.sid.agents

import akka.actor.FSM
import akka.persistence.fsm.PersistentFSM
import akka.persistence.fsm.PersistentFSM.FSMState
import beam.metasim.agents.Ack
import beam.metasim.playground.colin.{MATSimEvent, Transition}
import beam.metasim.playground.sid.agents.BeamAgent._
import beam.replanning.io.PlanElement
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.{Activity, Person, Plan}
import org.matsim.core.controler.events.ControlerEvent
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.reflect.classTag

// NOTE: companion objects used to define static methods and factory methods for a class

object BeamAgent {

  // states
  sealed trait BeamState extends FSMState

  case object Idle extends BeamState {
    override def identifier = "Idle"
  }

  case object Initialized extends BeamState {
    override def identifier = "Initialized"
  }

  trait InActivity extends BeamState {
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

  /**
    * Agent info consists of next MATSim plan element for agent to transition
    */
  case class BeamAgentInfo(planElement: PlanElement)

}


sealed trait Trigger

case object StartDay extends Trigger

case object InitActivity extends Trigger

case object SelectRoute extends Trigger

case object DepartActivity extends Trigger

/**
  * MemoryEvents play a dual role. They not only act as persistence in Akka, but
  * also get piped to the MATSimEvent Handler.
  */
//XXXX: May be useful to encapsulate the MATSimEvent and others in a
//      separate trait and use the `with` syntax.
sealed trait MemoryEvent extends MATSimEvent[_ <: ControlerEvent]

final case class ActivityTravelPlanMemory(planElement: PlanElement) extends MemoryEvent


/**
  * This FSM uses [[BeamState]] and [[BeamAgentInfo]] to define the state and
  * state data types.
  *
  * @param id create a new BeamAgent using the ID from the MATSim ID.
  */
class BeamAgent(id: Id[Person]) extends PersistentFSM[BeamState, BeamAgentInfo, MemoryEvent] {

  private val logger = LoggerFactory.getLogger(classOf[BeamAgent])

  startWith(Idle, BeamAgentInfo(null))

  when(Idle) {
    case Event(Idle, _) =>
      context.parent ! Ack
      goto(Initialized)
  }

  when(Initialized) {
    case Event(StartDay, _
    ) =>
      log.info(s"Agent with ID $id Received Start Event from scheduler")
      context.parent ! Ack
      goto(PerformingActivity)
  }

  when(PerformingActivity) {
    case Event(DepartActivity, _) =>
      stay()
  }



  onTransition {
    case Idle -> PerformingActivity => logger.debug("From init state to first activity")
    case PerformingActivity -> ChoosingMode => logger.debug("From activity to traveling")
    case ChoosingMode -> PerformingActivity => logger.debug("From traveling to activity")
  }

  override implicit def domainEventClassTag: ClassTag[MemoryEvent] = classTag[MemoryEvent]

  override def applyEvent(domainEvent: MemoryEvent, currentData: BeamAgentInfo): BeamAgentInfo = {
    domainEvent match {
      case ActivityTravelPlanMemory(newPlanElement: PlanElement) => {
        logger.info("Old travel sequence component " + currentData.planElement + " and new data" + newPlanElement)
        BeamAgentInfo(newPlanElement)
      }
    }
  }

  override def persistenceId: String = {
    id.toString
  }
}
