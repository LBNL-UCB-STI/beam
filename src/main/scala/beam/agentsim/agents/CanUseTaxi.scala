package beam.agentsim.agents

import akka.actor.ActorRef
import beam.agentsim.agents.BeamAgent.{BeamAgentInfo, BeamAgentState}
import beam.agentsim.agents.PersonAgent.{PersonData, RouteResponseWrapper}
import BeamAgent._
import PersonAgent._
import beam.agentsim.scheduler.BeamAgentScheduler.CompletionNotice
import beam.agentsim.scheduler.TriggerWithId
import beam.agentsim.scheduler.Trigger
import beam.router.RoutingModel.BeamTrip
import beam.sim.HasServices

/**
  * BEAM
  */
object CanUseTaxi{
  case class CanUseTaxiData(taxiAlternatives: Vector[Double] = Vector[Double]())

  case class TaxiInquiryResponseWrapper(tick: Double, triggerId: Long, alternatives: Vector[BeamTrip], timesToCustomer: Vector[Double]) extends Trigger

  case class ReserveTaxiResponseWrapper(tick: Double, triggerId: Long, taxi: Option[ActorRef], timeToCustomer: Double, tripChoice: BeamTrip) extends Trigger
}
trait CanUseTaxiData{
}
trait CanUseTaxi extends Behavior with TriggerShortcuts with HasServices{
//  this: PersonAgent => // Self type restricts this trait to only mix into a PersonAgent

  var taxiAlternatives: Vector[Double] = Vector[Double]()

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
    PerformingActivity -> {
      case Event(result: TaxiInquiryResponseWrapper, info: BeamAgentInfo[PersonData]) =>
        val completionNotice = completed(result.triggerId, schedule[PersonDepartureTrigger](result.tick, self))
        taxiAlternatives = result.timesToCustomer
        services.schedulerRef ! completionNotice
        goto(ChoosingMode) using stateData.copy(id, info.data.copy(currentAlternatives = result.alternatives))
    }
  )


}
