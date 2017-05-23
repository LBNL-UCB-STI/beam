package beam.agentsim.agents

import akka.actor.{ActorRef, FSM}
import beam.agentsim.agents.BeamAgent.{BeamAgentInfo, BeamAgentState}
import beam.agentsim.agents.PersonAgent.{PersonData, RouteResponseWrapper}
import beam.agentsim.routing.opentripplanner.OpenTripPlannerRouter.BeamTrip
import BeamAgent._
import PersonAgent._
import beam.agentsim.agents.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.events.AgentsimEventsBus.MatsimEvent
import org.matsim.api.core.v01.events.ActivityStartEvent
import org.matsim.api.core.v01.Id

/**
  * BEAM
  */
object CanUseTaxi{
  case class CanUseTaxiData(taxiAlternatives: Vector[Double] = Vector[Double]())

  case class TaxiInquiryResponseWrapper(tick: Double, triggerId: Long, alternatives: Vector[BeamTrip], timesToCustomer: Vector[Double]) extends Trigger

  case class ReserveTaxiResponseWrapper(tick: Double, triggerId: Long, taxi: Option[ActorRef], timeToCustomer: Double, tripChoice: BeamTrip) extends Trigger
}
trait CanUseTaxi extends Behavior with TriggerShortcuts{
  import beam.agentsim.sim.AgentsimServices._

  override def registerBehaviors(behaviors: Map[BeamAgentState,StateFunction]): Map[BeamAgentState,StateFunction] = {
    var newBehaviors = behaviors
    for((state, stateFunction) <- taxiBehaviors) {
      if(newBehaviors.contains(state)){
        newBehaviors += state -> (newBehaviors(state) orElse stateFunction)
      }else{
        newBehaviors += state -> stateFunction
      }
    }
    newBehaviors
  }

  private var taxiBehaviors = Map[BeamAgentState,StateFunction](
    Uninitialized -> {
      case Event(TriggerWithId(InitializeTrigger(tick), triggerId), _) =>
        goto(Initialized) replying CompletionNotice(triggerId)
    }
  )


}
