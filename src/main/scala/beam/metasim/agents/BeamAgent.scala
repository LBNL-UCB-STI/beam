package beam.metasim.agents

import akka.actor.{Actor, ActorRef, Cancellable, FSM, Props}
import BeamAgent._
import DecisionProtocol._
import org.matsim.api.core.v01.{Coord, TransportMode}
import org.matsim.core.utils.geometry.CoordUtils
import org.slf4j.LoggerFactory

import scala.reflect.ClassTag

object BeamAgent {

  // states
  sealed trait BeamState
  case object InitState extends BeamState
  case object InActivity extends BeamState
  case object Traveling extends BeamState

  case class BeamAgentInfo(currentLocation: Coord)

}

object DecisionProtocol{
  // TODO: Inherit from "BeamChoice" or some such abstract parent trait
  trait ModeChoice
  trait ActivityChoice

  case class ChooseMode(modeType: TransportMode) extends ModeChoice
}



class BeamAgent extends FSM[BeamState,BeamAgentInfo]{

  private val logger = LoggerFactory.getLogger(classOf[BeamAgent])


  startWith(InitState,BeamAgentInfo(CoordUtils.createCoord(0,0)))

  when(InitState)
//  {}
  {
    case Event()
  }
  when(InActivity)
//  {}
  when(Traveling)
//  {}

//  whenUnhandled

  onTransition {
    case InitState -> InActivity => logger.debug("From init state to first activity")
    case InActivity -> Traveling => logger.debug("From activity to traveling")
    case Traveling -> InActivity=> logger.debug("From traveling to activity")
  }

}
