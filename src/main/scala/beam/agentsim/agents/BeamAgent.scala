package beam.agentsim.agents

import akka.actor.{Cancellable, FSM}
import akka.persistence.fsm.PersistentFSM.FSMState
import beam.agentsim.agents.BeamAgent._
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import beam.agentsim.agents.BeamAgentScheduler._


object BeamAgent {

  // states
  trait BeamAgentState extends FSMState

  case object Uninitialized extends BeamAgentState { override def identifier = "Uninitialized" }

  case object Initialized extends BeamAgentState {
    override def identifier = "Initialized"
  }

  case object Finished extends BeamAgentState {
    override def identifier = "Finished"
  }

  case object Error extends BeamAgentState {
    override def identifier: String = s"Error!"
  }

  /**
    * Agent info consists of next MATSim plan element for agent to transition
    */
  sealed trait Info

  trait BeamAgentData

  case class NoData() extends BeamAgentData

  case class BeamAgentInfo[T <: BeamAgentData](id: Id[_], implicit val data: T) extends Info

}

case class InitializeTrigger(tick: Double) extends Trigger
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
  * This FSM uses [[BeamAgentState]] and [[BeamAgentInfo]] to define the state and
  * state data types.
  *
  */
trait BeamAgent[T <: BeamAgentData] extends FSM[BeamAgentState, BeamAgentInfo[T]] {

  def id: Id[_]

  def data: T

  startWith(Uninitialized, BeamAgentInfo[T](id, data))

  // Possible long-running process.
  def currentTask: Option[Cancellable] = None

  when(Uninitialized) {
    case Event(TriggerWithId(InitializeTrigger(tick),triggerId), _) =>
      goto(Initialized) replying CompletionNotice(triggerId)
  }

  when(Finished) {
    case Event(StopEvent, _) =>
      stop()
  }

  when(Error) {
    case Event(StopEvent, _) =>
      stop()
  }

  whenUnhandled {
    case Event(any, data) =>
      log.error(s"Unhandled event: $id $any $data")
      stay()
  }

  // Used to ensure that any long-running, potentially asynchronous process does not
  // need to return its value before the actor can be restarted.
  @scala.throws[Exception](classOf[Exception])
  override def postStop(): Unit = {
    currentTask foreach {
      _.cancel()
    }
    print(s"stop:${this.getClass.getSimpleName}, ")
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
