package beam.metasim.playground.sid.agents

import akka.actor.FSM
import beam.metasim.agents.{Ack, NoOp}
import beam.metasim.playground.sid.agents.BeamAgent._
import beam.playground.metasim.services.location.BeamLeg
import beam.replanning.io.PlanElement
import org.matsim.api.core.v01.population.{Activity, Leg}
import org.matsim.api.core.v01.{Coord, TransportMode}
import org.matsim.core.controler.events.StartupEvent
import org.matsim.core.utils.geometry.CoordUtils
import org.matsim.facilities.Facility
import org.slf4j.LoggerFactory

// NOTE: companion objects used to define static methods and factory methods for a class

object BeamAgent {

  // states
  sealed trait BeamState
  case object InitState extends BeamState
  case object InActivity extends BeamState
  case object Traveling extends BeamState

  // Agent info consists of next MATSim plan element for agent to transition to
  case class BeamAgentInfo(planElement: PlanElement)

}





class BeamAgent(id: String) extends FSM[BeamState,BeamAgentInfo]{

  private val logger = LoggerFactory.getLogger(classOf[BeamAgent])

  startWith(InitState,_)

  when(InitState){
    case Event(StartupEvent,BeamAgentInfo(planElement)) =>
      if (planElement.isInstanceOf[BeamLeg]){
        sender ! Ack
        goto(Traveling) using stateData.copy(planElement)
      }else stay()
  }

  //TODO: Implement the following:
  //  when(InActivity)
  ////  {}
  //  when(Traveling)
  ////  {}
  //
  ////  whenUnhandled

  onTransition {
    case InitState -> InActivity => logger.debug("From init state to first activity")
    case InActivity -> Traveling => logger.debug("From activity to traveling")
    case Traveling -> InActivity=> logger.debug("From traveling to activity")
  }

}
