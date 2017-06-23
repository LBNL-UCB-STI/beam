package beam.agentsim.agents.modalBehaviors

import beam.agentsim.agents.BeamAgent.BeamAgentInfo
import beam.agentsim.agents.{BeamAgent, PersonAgent, TriggerShortcuts}
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.TaxiManager.{TaxiInquiry, TaxiInquiryResponse}
import beam.agentsim.agents.modalBehaviors.ChoosesMode.{BeginModeChoiceTrigger, FinalizeModeChoiceTrigger}
import beam.agentsim.scheduler.{Trigger, TriggerWithId}
import beam.router.BeamRouter.{RoutingRequest, RoutingResponse}
import beam.router.Modes.BeamMode
import beam.router.RoutingModel.DiscreteTime
import beam.sim.HasServices

/**
  * BEAM
  */
trait ChoosesMode extends BeamAgent[PersonData] with TriggerShortcuts with HasServices {
  this: PersonAgent => // Self type restricts this trait to only mix into a PersonAgent
  import akka.pattern.{ask, pipe}

  var routerResult: Option[RoutingResponse] = None
  var taxiResult: Option[TaxiInquiryResponse] = None
  var hasReceivedCompleteChoiceTrigger = false

  def completeChoiceIfReady(theTriggerId: Option[Long]): State = {
    if (hasReceivedCompleteChoiceTrigger && routerResult != None && taxiResult != None) {
      // Choice happens here
      hasReceivedCompleteChoiceTrigger = false
      goto(Walking) using stateData.copy(triggerId = None, tick = None) replying completed(theTriggerId.get, schedule[TeleportationArrivalTrigger](stateData.tick.get, self))
    } else {
      stay() using stateData.copy(triggerId = theTriggerId)
    }
  }

  chainedWhen(ChoosingMode) {
    case Event(TriggerWithId(BeginModeChoiceTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      logInfo(s"inside ChoosesMode @ ${stateData.tick}")
      val nextAct = stateData.data.nextActivity.right.get // No danger of failure here
      services.beamRouter ! RoutingRequest(stateData.data.currentActivity, nextAct, DiscreteTime(stateData.tick.get.toInt), Vector(BeamMode.CAR, BeamMode.BIKE, BeamMode.WALK), id)
      //TODO parameterize search distance
      services.taxiManager ! TaxiInquiry(stateData.data.currentActivity.getCoord, 2000)
      stay() using stateData.copy(triggerId = Some(triggerId)) replying completed(triggerId, schedule[FinalizeModeChoiceTrigger](tick, self))
    case Event(theRouterResult: RoutingResponse, info: BeamAgentInfo[PersonData]) =>
      routerResult = Some(theRouterResult)
      completeChoiceIfReady(stateData.triggerId)
    case Event(theTaxiResult: TaxiInquiryResponse, info: BeamAgentInfo[PersonData]) =>
      taxiResult = Some(theTaxiResult)
      completeChoiceIfReady(stateData.triggerId)
    case Event(TriggerWithId(FinalizeModeChoiceTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      hasReceivedCompleteChoiceTrigger = true
      completeChoiceIfReady(Some(triggerId))
    case ev @ Event(_,_) =>
      stay()
  }

}
object ChoosesMode {
  case class BeginModeChoiceTrigger(tick: Double) extends Trigger
  case class FinalizeModeChoiceTrigger(tick: Double) extends Trigger
}
