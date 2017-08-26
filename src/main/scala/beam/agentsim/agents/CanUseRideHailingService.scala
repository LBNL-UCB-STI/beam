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
object CanUseRideHailingService{
  case class CanUseRideHailingServiceData(rideHailingAlternatives: Vector[Double] = Vector[Double]())

  case class RideHailingInquiryResponseWrapper(tick: Double, triggerId: Long, alternatives: Vector[BeamTrip], timesToCustomer: Vector[Double]) extends Trigger

  case class RideReservedResponseWrapper(tick: Double, triggerId: Long, taxi: Option[ActorRef], timeToCustomer: Double, tripChoice: BeamTrip) extends Trigger
}
trait CanUseRideHailingServiceData{
}
trait CanUseRideHailingService extends BeamAgent[PersonData] with TriggerShortcuts with HasServices{
  this: PersonAgent => // Self type restricts this trait to only mix into a PersonAgent

  var rideHailingAlternatives: Vector[Double] = Vector[Double]()


}
