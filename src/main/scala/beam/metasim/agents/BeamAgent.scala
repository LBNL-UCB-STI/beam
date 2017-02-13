package beam.metasim.agents

import akka.actor.FSM
import akka.persistence.fsm.PersistentFSM.FSMState
import org.matsim.api.core.v01.population.PlanElement
import org.slf4j.LoggerFactory
import BeamAgent._
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event


object BeamAgent {

  // states
  trait BeamState extends FSMState

  case object Idle extends BeamState {
    override def identifier = "Idle"
  }
  case object Initialized extends BeamState {
    override def identifier = "Initialized"
  }



  /**
    * Agent info consists of next MATSim plan element for agent to transition
    */
  trait Data
  case class BeamAgentInfo() extends Data



  case class AgentError(errorMsg: String)
}


/**
  * MemoryEvents play a dual role. They not only act as persistence in Akka, but
  * also get piped to the MATSimEvent Handler.
  */
//XXXX: May be useful to encapsulate the MATSimEvent and others in a
//      separate trait and use the `with` syntax.
sealed trait MemoryEvent extends Event

//final case class ActivityTravelPlanMemory(time: Double, currentTask: PlanElement) extends MemoryEvent {
//  override def getEventType: String = "ActivityTravelPlanMemory"
//}

/**
  * This FSM uses [[BeamState]] and [[BeamAgentInfo]] to define the state and
  * state data types.
  *
  */
abstract class BeamAgent(val id: Id[_]) extends FSM[BeamState, Data] {

  private val logger = LoggerFactory.getLogger(classOf[BeamAgent])

  startWith(Idle, BeamAgentInfo())

  when(Idle) {
    case Event(Initialize(data), _) =>
      data.agent ! Ack
      goto(Initialized)
  }

  when(Initialized) {
    case Event(Transition(data), _) =>
      stay()
  }

  def getId: Id[_] = {
    id
  }

}

    //    case Event(Transition((data, firstActivity), BeamAgentInfo(currentTask)
    //    ) =>
    //      assert(currentTask == null)
    //      log.info(s"Agent with ID $stateName Received Start Event from scheduler")
    //      context.parent ! Ack
    //      stay() // Default behavior... we want to override this!


  //TODO: Add Persistence back in
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
