package beam.agentsim.agents.modalBehaviors

import beam.agentsim.agents.BeamAgent.BeamAgentInfo
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.RideHailingManager.{RideHailingInquiry, RideHailingInquiryResponse}
import beam.agentsim.agents.modalBehaviors.ChoosesMode.{BeginModeChoiceTrigger, ChoiceCalculator, FinalizeModeChoiceTrigger}
import beam.agentsim.agents.{BeamAgent, PersonAgent, RideHailingManager, TriggerShortcuts}
import beam.agentsim.events.AgentsimEventsBus.MatsimEvent
import beam.agentsim.scheduler.{Trigger, TriggerWithId}
import beam.router.BeamRouter.{RoutingRequest, RoutingResponse}
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode._
import beam.router.RoutingModel.{BeamTime, BeamTrip, DiscreteTime}
import beam.sim.HasServices
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.PersonDepartureEvent

import scala.util.Random

/**
  * BEAM
  */
trait ChoosesMode extends BeamAgent[PersonData] with TriggerShortcuts with HasServices {
  this: PersonAgent => // Self type restricts this trait to only mix into a PersonAgent

  val choiceCalculator: ChoiceCalculator = ChoosesMode.mnlChoice
  var routerResult: Option[RoutingResponse] = None
  var taxiResult: Option[RideHailingInquiryResponse] = None
  var hasReceivedCompleteChoiceTrigger = false

  def completeChoiceIfReady(): State = {
    if (hasReceivedCompleteChoiceTrigger && routerResult.isDefined && taxiResult.isDefined) {

      val chosenTrip = choiceCalculator(routerResult.get.itineraries)

      hasReceivedCompleteChoiceTrigger = false
      val theTriggerIdAsLong = _currentTriggerId.get
      val theTick = _currentTick.get
      _currentTriggerId = None
      _currentTick = None

      beamServices.agentSimEventsBus.publish(MatsimEvent(new PersonDepartureEvent(theTick, id,
        stateData.data.currentActivity.getLinkId, chosenTrip.tripClassifier.matsimMode)))

      if(chosenTrip.legs.isEmpty) {
        log.error(s"No further PersonDepartureTrigger is going to be scheduled after triggerId=$theTriggerIdAsLong ")
        beamServices.schedulerRef ! completed(triggerId = theTriggerIdAsLong)
        goto(BeamAgent.Error)
      } else {
        if(id.toString.equals("104793")){
          val i = 0
        }
        beamServices.schedulerRef ! completed(triggerId = theTriggerIdAsLong, schedule[PersonDepartureTrigger](chosenTrip.legs.keys.head.startTime, self))
        goto(Waiting) using BeamAgentInfo(id, stateData.data.copy(currentRoute = chosenTrip))
      }
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
      logInfo(s"inside ChoosesMode @ ${tick}")
      if(id.toString.equals("104793")){
        val i = 0
      }
      val nextAct = stateData.data.nextActivity.right.get // No danger of failure here
      val departTime = DiscreteTime(tick.toInt)
      //val departTime = BeamTime.within(stateData.data.currentActivity.getEndTime.toInt)
      //TODO need the StreetVehicles in here
      beamServices.beamRouter ! RoutingRequest(stateData.data.currentActivity, nextAct, departTime, Vector(BeamMode.CAR, BeamMode.BIKE, BeamMode.WALK, BeamMode.TRANSIT), id)
      //TODO parameterize search distance
      val pickUpLocation = stateData.data.currentActivity.getCoord
      beamServices.taxiManager ! RideHailingInquiry(RideHailingManager.nextTaxiInquiryId,
        Id.create(info.id.toString, classOf[PersonAgent]), pickUpLocation, departTime, 2000, nextAct.getCoord)

      stay() replying completed(triggerId, schedule[FinalizeModeChoiceTrigger](tick, self))
    /*
     * Receive and store data needed for choice.
     */
    case Event(theRouterResult: RoutingResponse, info: BeamAgentInfo[PersonData]) =>
      routerResult = Some(theRouterResult)
      completeChoiceIfReady()
    case Event(theTaxiResult: RideHailingInquiryResponse, info: BeamAgentInfo[PersonData]) =>
      taxiResult = Some(theTaxiResult)
      completeChoiceIfReady()
    /*
     * Finishing choice.
     */
    case Event(TriggerWithId(FinalizeModeChoiceTrigger(tick), theTriggerId), info: BeamAgentInfo[PersonData]) =>
      _currentTriggerId = Some(theTriggerId)
      _currentTick = Some(tick)
      hasReceivedCompleteChoiceTrigger = true
      completeChoiceIfReady()
  }

}
object ChoosesMode {
  type ChoiceCalculator = (Vector[BeamTrip]) => BeamTrip

  case class BeginModeChoiceTrigger(tick: Double) extends Trigger
  case class FinalizeModeChoiceTrigger(tick: Double) extends Trigger

  def mnlChoice(alternatives: Vector[BeamTrip]): BeamTrip = {
    var alternativesWithTaxi = Vector[BeamTrip]()
    alternativesWithTaxi = alternativesWithTaxi ++ alternatives
    var containsDriveAlt = -1
    var altModesAndTimes: Vector[(BeamMode, Double)] = for (i <- alternatives.indices.toVector) yield {
      val alt = alternatives(i)
      val altMode = if (alt.legs.keys.size == 1) {
        alt.legs.keys.head.mode
      } else {
        if (alt.legs.keys.head.mode.equals(CAR)) {
          containsDriveAlt = i
          CAR
        } else {
          TRANSIT
        }
      }
      val travelTime = (for (leg <- alt.legs.keys) yield leg.duration).foldLeft(0.0) {
        _ + _
      }
      (altMode, travelTime)
    }
    //    if (containsDriveAlt >= 0 && taxiAlternatives.nonEmpty) {
    //      //TODO replace magic number here (5 minute wait time) with calculated wait time
    //      val minTimeToCustomer = taxiAlternatives.foldLeft(Double.PositiveInfinity)((r, c) => if (c < r) {
    //        c
    //      } else r)
    //      altModesAndTimes = altModesAndTimes :+ (TAXI, (for (alt <- altModesAndTimes if alt._1.equals(CAR)) yield alt._2 + minTimeToCustomer).head)
    //      alternativesWithTaxi = alternativesWithTaxi :+ BeamTrip(alternatives(containsDriveAlt).legs.map(leg => leg.copy(mode = if (leg.mode.equals(CAR)) {
    //        TAXI
    //      } else {
    //        leg.mode
    //      })))
    //    }
    val altUtilities = for (alt <- altModesAndTimes) yield altUtility(alt._1, alt._2)
    val sumExpUtilities = altUtilities.foldLeft(0.0)(_ + math.exp(_))
    val altProbabilities = for (util <- altUtilities) yield math.exp(util) / sumExpUtilities
    val cumulativeAltProbabilities = altProbabilities.scanLeft(0.0)(_ + _)
    //TODO replace with RNG in services
    val randDraw = Random.nextDouble()
    val chosenIndex = for (i <- 1 until cumulativeAltProbabilities.length if randDraw < cumulativeAltProbabilities(i)) yield i - 1
    if(chosenIndex.size > 0) {
//      alternativesWithTaxi(chosenIndex.head).copy(choiceUtility = sumExpUtilities)
      alternativesWithTaxi(chosenIndex.head)
    } else {
      BeamTrip.empty
    }
  }

  def altUtility(mode: BeamMode, travelTime: Double): Double = {
    val intercept = if(mode.equals(CAR)){ -3.0 }else{ if(mode.equals(TAXI)){ -5.0}else{0.0} }
    intercept + -0.001 * travelTime
  }

  def randomChoice(alternatives: Vector[BeamTrip]): BeamTrip = {
    Random.shuffle(alternatives.toList).head
  }
}
