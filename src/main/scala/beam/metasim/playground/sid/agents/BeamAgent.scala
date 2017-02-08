package beam.metasim.playground.sid.agents

import akka.actor.FSM
import akka.persistence.fsm.PersistentFSM
import akka.persistence.fsm.PersistentFSM.FSMState
import beam.metasim.agents.Ack
import beam.metasim.playground.colin.MATSimEvent
import beam.metasim.playground.sid.agents.BeamAgent._
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.{Activity, Person, PlanElement}
import org.matsim.core.controler.events.ControlerEvent
import org.slf4j.LoggerFactory

import scala.reflect.{ClassTag, classTag}

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
  case class BeamAgentInfo(currentTask: PlanElement)

  case class AgentError(errorMsg: String)

}


abstract class Trigger

case object StartDay extends Trigger

case class InitActivity(nextActivity: PlanElement)  extends Trigger

case object SelectRoute extends Trigger

case class DepartActivity(nextActivity: PlanElement) extends Trigger

/**
  * MemoryEvents play a dual role. They not only act as persistence in Akka, but
  * also get piped to the MATSimEvent Handler.
  */
//XXXX: May be useful to encapsulate the MATSimEvent and others in a
//      separate trait and use the `with` syntax.
sealed trait MemoryEvent extends MATSimEvent[ControlerEvent]

final case class ActivityTravelPlanMemory(currentTask: PlanElement) extends MemoryEvent

/**
  * This FSM uses [[BeamState]] and [[BeamAgentInfo]] to define the state and
  * state data types.
  *
  */
class BeamAgent extends FSM[BeamState, BeamAgentInfo] {

  private val logger = LoggerFactory.getLogger(classOf[BeamAgent])

  startWith(Idle, BeamAgentInfo(null))

  when(Idle) {
    case Event(StartDay, _) =>
      context.parent ! Ack
      goto(Initialized)
  }

  when(Initialized) {
    case Event(InitActivity(firstActivity), BeamAgentInfo(currentTask)
    ) =>
      assert(currentTask == null)
      log.info(s"Agent with ID $stateName Received Start Event from scheduler")
      context.parent ! Ack
      goto(PerformingActivity) using stateData.copy(currentTask = firstActivity.asInstanceOf[Activity])
  }


  //
  //  override implicit def domainEventClassTag: ClassTag[MemoryEvent] = classTag[MemoryEvent]
  //
  //  override def applyEvent(domainEvent: MemoryEvent, currentData: BeamAgentInfo): BeamAgentInfo = {
  //    domainEvent match {
  //      case ActivityTravelPlanMemory(newPlanElement: PlanElement) => {
  //        logger.info("Old travel sequence component " + currentData.planElement + " and new data" + newPlanElement)
  //        BeamAgentInfo(newPlanElement)
  //      }
  //    }
  //  }
  //
  //  override def persistenceId: String = {
  //    "Name"
  //  }
}
