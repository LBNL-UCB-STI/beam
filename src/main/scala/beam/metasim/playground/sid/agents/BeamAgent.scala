package beam.metasim.playground.sid.agents

import akka.actor.FSM
import beam.metasim.agents.Ack
import beam.metasim.playground.sid.agents.BeamAgent._
import beam.metasim.playground.sid.events.ActorSimulationEvents._
import beam.replanning.io.PlanElement
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

// NOTE: companion objects used to define static methods and factory methods for a class

object BeamAgent {

  // states
  sealed trait BeamState

  case object InitState extends BeamState

  case object WaitingForStart extends BeamState

  case object InActivity extends BeamState

  case object Traveling extends BeamState

  /**
    * Agent info consists of next MATSim plan element for agent to transition
    */
  case class BeamAgentInfo(planElement: PlanElement)

}


/**
  * This FSM uses [[BeamState]] and [[BeamAgentInfo]] to define the state and
  * state data types.
  *
  * @param id create a new BeamAgent using the ID from the MATSim ID.
  */
class BeamAgent(id: String) extends FSM[BeamState, BeamAgentInfo] {

  private val logger = LoggerFactory.getLogger(classOf[BeamAgent])

  startWith(InitState, BeamAgentInfo(null))

  when(InitState) {
    case Event(Await, _) =>
      context.parent ! Ack
      goto(WaitingForStart)
  }

  when(WaitingForStart, stateTimeout = 20 milliseconds) {
    case Event(Start, _) =>
      log.info(s"Agent with ID $id Received Start Event from scheduler")
      context.parent ! AgentReady
      goto(InActivity)

    case Event(StateTimeout, _) =>
      context.parent ! TimeOut
      goto(InitState)

  }
  when(InActivity) {
    case Event(FinishLeg, _) =>
      stay()
  }


  //TODO: Implement the following:
  //  when(Activity)
  ////  {}
  //    when(Traveling)
  //      {
  //        case Event(FinishLeg,)
  //      }
  //
  ////  whenUnhandled

  onTransition {
    case InitState -> InActivity => logger.debug("From init state to first activity")
    case InActivity -> Traveling => logger.debug("From activity to traveling")
    case Traveling -> InActivity => logger.debug("From traveling to activity")
  }

}
