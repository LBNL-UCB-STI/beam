package beam.agentsim.agents.modalBehaviors

import beam.agentsim.agents.BeamAgent.BeamAgentInfo
import beam.agentsim.agents.{BeamAgent, PersonAgent, TriggerShortcuts}
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.TaxiManager.{TaxiInquiry, TaxiInquiryResponse}
import beam.agentsim.agents.modalBehaviors.ChoosesMode.{BeginModeChoiceTrigger, FinalizeModeChoiceTrigger}
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
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

  var routerResult: Option[RoutingResponse] = None
  var taxiResult: Option[TaxiInquiryResponse] = None
  var hasReceivedCompleteChoiceTrigger = false

  def completeChoiceIfReady(theTriggerId: Option[Long]): State = {
    if (hasReceivedCompleteChoiceTrigger && routerResult != None && taxiResult != None) {
      // Choice happens here
      hasReceivedCompleteChoiceTrigger = false
      val theTriggerIdAsLong = if(theTriggerId == None){ stateData.triggerId.get }else{ theTriggerId.get }
      services.schedulerRef ! completed(theTriggerIdAsLong,schedule[TeleportationArrivalTrigger](stateData.tick.get, self))
      goto(Walking) using stateData.copy(triggerId = None, tick = None)
    } else {
      stay()
    }
  }

  chainedWhen(ChoosingMode) {
    /*
     * Begin Choice Process
     *
     * When we begin the mode choice process, we send out requests for data that we need from other system components.
     * Then we reply with a completion notice and schedule the finalize choice trigger.
     */
    case Event(TriggerWithId(BeginModeChoiceTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      logInfo(s"inside ChoosesMode @ ${stateData.tick}")
      val nextAct = stateData.data.nextActivity.right.get // No danger of failure here
      services.beamRouter ! RoutingRequest(stateData.data.currentActivity, nextAct, DiscreteTime(stateData.tick.get.toInt), Vector(BeamMode.CAR, BeamMode.BIKE, BeamMode.WALK, BeamMode.TRANSIT), id)
      //TODO parameterize search distance
      services.taxiManager ! TaxiInquiry(stateData.data.currentActivity.getCoord, 2000)
      stay() using stateData.copy(triggerId = Some(triggerId)) replying completed(triggerId, schedule[FinalizeModeChoiceTrigger](tick, self))
    /*
     * Receive and store data needed for choice.
     */
    case Event(theRouterResult: RoutingResponse, info: BeamAgentInfo[PersonData]) =>
      routerResult = Some(theRouterResult)
      completeChoiceIfReady(None)
    case Event(theTaxiResult: TaxiInquiryResponse, info: BeamAgentInfo[PersonData]) =>
      taxiResult = Some(theTaxiResult)
      completeChoiceIfReady(None)
    /*
     * Finishing choice.
     */
    case Event(TriggerWithId(FinalizeModeChoiceTrigger(tick), theTriggerId), info: BeamAgentInfo[PersonData]) =>
      hasReceivedCompleteChoiceTrigger = true
      val result : State = completeChoiceIfReady(Some(theTriggerId))
      if(result.stateName == ChoosesMode){
        stay() using info.copy(triggerId = Some(theTriggerId))
      }else{
        result
      }
  }

}
object ChoosesMode {
  case class BeginModeChoiceTrigger(tick: Double) extends Trigger
  case class FinalizeModeChoiceTrigger(tick: Double) extends Trigger
}
