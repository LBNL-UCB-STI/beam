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
trait CanUseTaxi extends BeamAgent[PersonData] with TriggerShortcuts with HasServices{
  this: PersonAgent => // Self type restricts this trait to only mix into a PersonAgent

  var taxiAlternatives: Vector[Double] = Vector[Double]()

  chainedWhen(Uninitialized){
      case Event(TriggerWithId(InitializeTrigger(tick), triggerId), _) =>
        log.info("From CanUseTaxi")
        goto(Initialized)
  }

  when(PerformingActivity) {
    case Event(result: TaxiInquiryResponseWrapper, info: BeamAgentInfo[PersonData]) =>
      val completionNotice = completed(result.triggerId, schedule[PersonDepartureTrigger](result.tick, self))
      taxiAlternatives = result.timesToCustomer
      services.schedulerRef ! completionNotice
      goto(ChoosingMode) using stateData.copy(id, info.data.copy(currentAlternatives = result.alternatives))
  }


}
