package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.Props
import akka.actor.ActorRef
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.BeamAgentScheduler._
import beam.agentsim.agents.PersonAgent.{Driving, _}
import beam.agentsim.agents.TaxiAgent.DropOffCustomer
import beam.agentsim.agents.TaxiManager.{ReserveTaxi, ReserveTaxiConfirmation, TaxiInquiry, TaxiInquiryResponse}
import beam.agentsim.events.AgentsimEventsBus.MatsimEvent
import beam.agentsim.events.{PathTraversalEvent, PointProcessEvent}
import beam.agentsim.routing.RoutingMessages.RoutingRequest
import beam.agentsim.routing.opentripplanner.OpenTripPlannerRouter.{BeamLeg, BeamTrip, RoutingResponse}
import beam.utils.DebugLib
import glokka.Registry
import org.matsim.api.core.v01.events._
import org.matsim.api.core.v01.population._
import org.matsim.api.core.v01.{Id, TransportMode}
import org.matsim.core.api.experimental.events.AgentWaitingForPtEvent
import org.matsim.pt.transitSchedule.api.TransitStopFacility
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random

/**
  * Created by sfeygin on 2/6/17.
  */
object PersonAgent {

  val timeToChooseMode: Double = 0.0
  val minActDuration: Double = 0.0
  val teleportWalkDuration = 0.0

  private val logger = LoggerFactory.getLogger(classOf[PersonAgent])

  type ChoiceCalculator = (Vector[BeamTrip], Vector[Double]) => BeamTrip

  def mnlChoice(alternatives: Vector[BeamTrip], taxiAlternatives: Vector[Double]): BeamTrip = {
    var alternativesWithTaxi = Vector[BeamTrip]()
    alternativesWithTaxi = alternativesWithTaxi ++ alternatives
    var containsDriveAlt = -1
    var altModesAndTimes : Vector[(String, Double)] = for(i <- alternatives.indices.toVector) yield {
      val alt = alternatives(i)
      val altMode = if (alt.legs.length == 1) {
        alt.legs.head.mode
      } else {
        if (alt.legs(1).mode.equalsIgnoreCase("CAR")) {
          containsDriveAlt = i
          "CAR"
        } else {
          "PT"
        }
      }
      val travelTime = (for (leg <- alt.legs) yield leg.travelTime).foldLeft(0.0) {
        _ + _
      }
      (altMode, travelTime)
    }
    if(containsDriveAlt >= 0 && taxiAlternatives.nonEmpty){
      //TODO replace magic number here (5 minute wait time) with calculated wait time
      val minTimeToCustomer = taxiAlternatives.foldLeft(Double.PositiveInfinity)((r,c) => if(c<r){c}else r)
      altModesAndTimes = altModesAndTimes :+ ("TAXI",(for(alt <- altModesAndTimes if alt._1.equals("CAR"))yield alt._2 + minTimeToCustomer).head)
      alternativesWithTaxi = alternativesWithTaxi :+ BeamTrip(alternatives(containsDriveAlt).legs.map(leg => leg.copy(mode = if(leg.mode.equals("CAR")){ "TAXI" }else{ leg.mode })))
    }
    val altUtilities = for(alt <- altModesAndTimes)yield altUtility(alt._1, alt._2)
    val sumExpUtilities = altUtilities.foldLeft(0.0)( _ + math.exp(_) )
    val altProbabilities = for( util <- altUtilities) yield math.exp(util) / sumExpUtilities
    val cumulativeAltProbabilities = altProbabilities.scanLeft(0.0)( _ + _ )
    val randDraw = Random.nextDouble()
    val chosenIndex = for(i <- 1 until cumulativeAltProbabilities.length if randDraw < cumulativeAltProbabilities(i)) yield i - 1
    alternativesWithTaxi(chosenIndex.head).copy(choiceUtility = sumExpUtilities)
  }
  def altUtility(mode: String, travelTime: Double): Double = {
    val intercept = if(mode.equalsIgnoreCase("CAR")){ -5.0 }else{ if(mode.equalsIgnoreCase("TAXI")){ 0.0}else{0.0} }
    intercept + -0.001 * travelTime
  }
  def randomChoice(alternatives: Vector[BeamTrip], taxiAlternatives: Vector[ActorRef]): BeamTrip = {
    Random.shuffle(alternatives.toList).head
  }


  def randomChoice(alternatives: Vector[BeamTrip]): BeamTrip = Random.shuffle(alternatives.toList).head

  // syntactic sugar for props creation
  def props(personId: Id[PersonAgent], personData: PersonData) = Props(classOf[PersonAgent], personId, personData)

  //////////////////////////////
  // PersonData Begin... //
  /////////////////////////////
  object PersonData {

    import scala.collection.JavaConverters._

    /**
      * [[PersonData]] factory method to assist in  creating [[PersonData]].
      * Permits creation of
      *
      * @param plan : The plan having at least some `Activities`
      * @return `PersonData`
      */
    def apply(plan: Plan): PersonData = PersonData(planToVec(plan), 0, BeamTrip.noneTrip, Vector[BeamTrip](), Vector[Double](), mnlChoice, None)

    def planToVec(plan: Plan): Vector[Activity] = {
      scala.collection.immutable.Vector.empty[Activity] ++ plan.getPlanElements.asScala.filter(p => p.isInstanceOf[Activity]).map(p => p.asInstanceOf[Activity])
    }

    implicit def plan2PersonData(plan: Plan): PersonData = PersonData(plan)

  }

  case class PersonData(activityChain: Vector[Activity], currentActivityIndex: Int = 0,
                        currentRoute: BeamTrip = BeamTrip.noneTrip,
                        currentAlternatives: Vector[BeamTrip] = Vector[BeamTrip](),
                        taxiAlternatives: Vector[Double] = Vector[Double](),
                        choiceCalculator: ChoiceCalculator,
                        currentVehicle: Option[ActorRef]) extends BeamAgentData {

    def activityOrMessage(ind: Int, msg: String): Either[String, Activity] = {
      if (ind < 0 || ind >= activityChain.length) Left(msg) else Right(activityChain(ind))
    }

    def currentActivity: Activity = activityChain(currentActivityIndex)

    def nextActivity: Either[String, Activity] = {
      activityOrMessage(currentActivityIndex + 1, "plan finished")
    }

    def prevActivity: Either[String, Activity] = {
      activityOrMessage(currentActivityIndex - 1, "at start")
    }


  }


  // End PersonData ~

  sealed trait InActivity extends BeamAgentState

  case object PerformingActivity extends InActivity {
    override def identifier = "Performing an Activity"
  }

  sealed trait Traveling extends BeamAgentState

  case object ChoosingMode extends Traveling {
    override def identifier = "ChoosingMode"
  }

  case object Driving extends Traveling {
    override def identifier = "Driving"
  }

  case object InTaxi extends Traveling {
    override def identifier = "InTaxi"
  }

  case object Walking extends Traveling {
    override def identifier = "Walking"
  }

  case object Waiting extends Traveling {
    override def identifier = "Waiting"
  }

  case object OnTransit extends Traveling {
    override def identifier = "OnTransit"
  }

  case object Boarding extends Traveling {
    override def identifier = "Boarding"
  }

  case object Alighting extends Traveling {
    override def identifier = "Alighting"
  }

  case class ResetPersonAgent(tick: Double) extends Trigger

  case class ActivityStartTrigger(tick: Double) extends Trigger

  case class ActivityEndTrigger(tick: Double) extends Trigger

  case class RouteResponseWrapper(tick: Double, triggerId: Long, alternatives: Vector[BeamTrip]) extends Trigger

  case class TaxiInquiryResponseWrapper(tick: Double, triggerId: Long, alternatives: Vector[BeamTrip], timesToCustomer: Vector[Double]) extends Trigger

  case class ReserveTaxiResponseWrapper(tick: Double, triggerId: Long, taxi: Option[ActorRef], timeToCustomer: Double, tripChoice: BeamTrip) extends Trigger

  case class FinishWrapper(tick: Double, triggerId: Long) extends Trigger

  case class NextActivityWrapper(tick: Double, triggerId: Long) extends Trigger

  case class PersonDepartureTrigger(tick: Double) extends Trigger

  case class PersonEntersVehicleTrigger(tick: Double) extends Trigger

  case class PersonLeavesVehicleTrigger(tick: Double) extends Trigger

  case class PersonEntersTaxiTrigger(tick: Double) extends Trigger

  case class PersonLeavesTaxiTrigger(tick: Double) extends Trigger

  case class PersonEntersBoardingQueueTrigger(tick: Double) extends Trigger

  case class PersonEntersAlightingQueueTrigger(tick: Double) extends Trigger

  case class PersonArrivesTransitStopTrigger(tick: Double) extends Trigger

  case class PersonArrivalTrigger(tick: Double) extends Trigger

  case class TeleportationArrivalTrigger(tick: Double) extends Trigger


}

class PersonAgent(override val id: Id[PersonAgent], override val data: PersonData) extends BeamAgent[PersonData] {

  import akka.pattern.{ask, pipe}
  import beam.agentsim.sim.AgentsimServices._

  private implicit val timeout = akka.util.Timeout(5000, TimeUnit.SECONDS)

  when(Uninitialized) {
    case Event(TriggerWithId(InitializeTrigger(tick), triggerId), _) =>
      goto(Initialized) replying CompletionNotice(triggerId)
  }

  when(Initialized) {
    case Event(TriggerWithId(ActivityStartTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val currentActivity = info.data.currentActivity
      agentSimEventsBus.publish(MatsimEvent(new ActivityStartEvent(tick, id, currentActivity.getLinkId, currentActivity.getFacilityId, currentActivity.getType)))
      // Since this is the first activity of the day, we don't increment the currentActivityIndex
      logInfo(s"starting at ${currentActivity.getType}")
      goto(PerformingActivity) using info replying completed(triggerId, schedule[ActivityEndTrigger](currentActivity.getEndTime))
  }

  when(PerformingActivity) {
    case Event(TriggerWithId(ActivityEndTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val currentActivity = info.data.currentActivity

      // Activity ends, so publish to EventBus
      agentSimEventsBus.publish(MatsimEvent(new ActivityEndEvent(tick, id, currentActivity.getLinkId, currentActivity.getFacilityId, currentActivity.getType)))

      info.data.nextActivity.fold(
        msg => {
          logInfo(s"didn't get nextActivity because $msg")
          self ! FinishWrapper(tick, triggerId)
        },
        nextAct => {
          logInfo(s"going to ${nextAct.getType}")
          val routerFuture = (beamRouter ? RoutingRequest(info.data.currentActivity, nextAct, tick, id)).mapTo[RoutingResponse] map { result =>
            val theRoute = result.itinerary
            RouteResponseWrapper(tick, triggerId, theRoute)
          } pipeTo self
        }
      )
      stay()
    case Event(routeResult: RouteResponseWrapper, info: BeamAgentInfo[PersonData]) =>
      val taxiManagerFuture = (taxiManager ? TaxiInquiry(info.data.currentActivity.getCoord,2000)).mapTo[TaxiInquiryResponse] map { taxiResult =>
        TaxiInquiryResponseWrapper(routeResult.tick, routeResult.triggerId, routeResult.alternatives, taxiResult.timesToCustomer)
      } pipeTo self
      stay()
    case Event(result: TaxiInquiryResponseWrapper, info: BeamAgentInfo[PersonData]) =>
      val completionNotice = completed(result.triggerId, schedule[PersonDepartureTrigger](result.tick))
      if (info.id.toString.equals("3")) {
        DebugLib.emptyFunctionForSettingBreakPoint()
      }
      // Send CN directly to scheduler.
      // Can't reply as usual here, since execution context post-pipe captures self as sender via closure.
      schedulerRef ! completionNotice
      goto(ChoosingMode) using stateData.copy(id, info.data.copy(currentAlternatives = result.alternatives, taxiAlternatives = result.timesToCustomer))
    case Event(msg: FinishWrapper, info: BeamAgentInfo[PersonData]) =>
      schedulerRef ! CompletionNotice(msg.triggerId)
      goto(Error)
  }

  // TODO: Deal with case of arriving too late at next activity
  when(ChoosingMode) {
    case Event(TriggerWithId(PersonDepartureTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      if (info.data.currentAlternatives.isEmpty) {
        logError("going to Error b/c empty route received")
        goto(Error) replying CompletionNotice(triggerId)
      } else {
        val tripChoice: BeamTrip = info.data.choiceCalculator(info.data.currentAlternatives, info.data.taxiAlternatives)
        val procData = procStateData(tripChoice, tick)
        // Here, we actually need to do an extra step of look-ahead to get the correct (non-walk) mode
        val restTrip = procData.restTrip
        restTrip.legs.headOption match {
          case Some(BeamLeg(_, "WALK",_, _)) | Some(BeamLeg(_, "CAR",_, _)) | Some(BeamLeg(_, "WAITING",_, _)) =>
            agentSimEventsBus.publish(MatsimEvent(new PointProcessEvent(procData.nextLeg.startTime,id,"CHOICE",info.data.currentActivity.getCoord,intensity = tripChoice.choiceUtility)))
          case _ =>
          //do nothing
        }
        restTrip.legs.headOption match {
          case Some(BeamLeg(_, "WALK", _, _)) if restTrip.legs.length == 1 =>
            agentSimEventsBus.publish(MatsimEvent(new PersonDepartureEvent(tick, id, info.data.currentActivity.getLinkId, TransportMode.walk)))
            goto(Walking) using BeamAgentInfo(id, stateData.data.copy(currentRoute = tripChoice)) replying
              completed(triggerId, schedule[TeleportationArrivalTrigger](tick + timeToChooseMode))
          case Some(BeamLeg(_, "WALK", _, _)) if restTrip.legs.length > 1 =>
            agentSimEventsBus.publish(MatsimEvent(new PersonDepartureEvent(tick, id, info.data.currentActivity.getLinkId, TransportMode.walk)))
            goto(Walking) using BeamAgentInfo(id, stateData.data.copy(currentRoute = tripChoice)) replying
              completed(triggerId, schedule[TeleportationArrivalTrigger](tick + timeToChooseMode))
          case Some(BeamLeg(_, "CAR", _, _)) if restTrip.legs.length > 1 =>
            agentSimEventsBus.publish(MatsimEvent(new PersonDepartureEvent(tick, id, info.data.currentActivity.getLinkId, TransportMode.car)))
            goto(Walking) using BeamAgentInfo(id, stateData.data.copy(currentRoute = tripChoice)) replying
              completed(triggerId, schedule[PersonEntersVehicleTrigger](tick + timeToChooseMode))
          case Some(BeamLeg(_, "TAXI",_, _)) if restTrip.legs.length > 1 =>
            (taxiManager ? ReserveTaxi(info.data.currentActivity.getCoord)).mapTo[ReserveTaxiConfirmation] map { result =>
              ReserveTaxiResponseWrapper(tick, triggerId, result.taxi, result.timeToCustomer, tripChoice)
            } pipeTo self
            stay()
          case Some(BeamLeg(_, "WAITING",_, _)) =>
            agentSimEventsBus.publish(MatsimEvent(new PersonDepartureEvent(tick, id, info.data.currentActivity.getLinkId, TransportMode.pt)))
            goto(Walking) using BeamAgentInfo(id, stateData.data.copy(currentRoute = tripChoice)) replying
              completed(triggerId, schedule[PersonArrivesTransitStopTrigger](tick + timeToChooseMode))
          case Some(BeamLeg(_, _, _,_)) =>
            logError(s"going to Error on trigger $triggerId in ChoosingMode due to unknown mode")
            goto(Error) using stateData.copy(id, stateData.data.copy()) replying CompletionNotice(triggerId)
          case None | Some(_) =>
            logError(s"going to Error on trigger $triggerId in ChoosingMode due to no next leg")
            goto(Error) using stateData.copy(id, stateData.data.copy()) replying CompletionNotice(triggerId)
        }
      }
    case Event(ReserveTaxiResponseWrapper(tick, triggerId, taxi, timeToCustomer, tripChoice),info: BeamAgentInfo[PersonData]) =>
      taxi match {
        case Some(theTaxi) =>
          agentSimEventsBus.publish(MatsimEvent(new PersonDepartureEvent(tick, id, info.data.currentActivity.getLinkId, TransportMode.car)))
          schedulerRef ! completed(triggerId, schedule[PersonEntersTaxiTrigger](tick + timeToCustomer))
          goto(Walking) using BeamAgentInfo(id, info.data.copy(currentRoute = tripChoice, currentVehicle = taxi))
        case None =>
          logError(s"going to Error on trigger $triggerId in ChoosingMode due to no taxi")
          schedulerRef ! CompletionNotice(triggerId)
          goto(Error) using stateData.copy(id, stateData.data.copy())
      }
  }

  // TODO: Get Vehicle ids and implement currentVehicle as member of PersonData
  when(Walking) {
    // -> Driving
    case Event(TriggerWithId(PersonEntersVehicleTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val procData = procStateData(info.data.currentRoute, tick)
      agentSimEventsBus.publish(MatsimEvent(new PersonEntersVehicleEvent(tick, id, Id.createVehicleId(s"car_$id"))))
      goto(Driving) using BeamAgentInfo(id, stateData.data.copy(currentRoute = procData.restTrip)) replying
        completed(triggerId, schedule[PersonLeavesVehicleTrigger](procData.nextStart))

    // -> Taxi
    case Event(TriggerWithId(PersonEntersTaxiTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val procData = procStateData(info.data.currentRoute, tick)
      agentSimEventsBus.publish(MatsimEvent(new PersonEntersVehicleEvent(tick, id, Id.createVehicleId(s"taxi_$id"))))
      goto(InTaxi) using BeamAgentInfo(id, stateData.data.copy(currentRoute = procData.restTrip)) replying
        completed(triggerId, schedule[PersonLeavesTaxiTrigger](procData.nextStart))

    // -> Transit
    case Event(TriggerWithId(PersonArrivesTransitStopTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val procData = procStateData(info.data.currentRoute, tick)
      agentSimEventsBus.publish(MatsimEvent(PathTraversalEvent(tick, id, procData.nextLeg.graphPath, procData.nextLeg.mode)))
      goto(Waiting) using stateData.copy(id, info.data.copy(currentRoute = procData.restTrip)) replying
        completed(triggerId, schedule[PersonEntersBoardingQueueTrigger](procData.nextStart))

    // TODO: Transfer on Transit

    //-> NextActivity
    case Event(TriggerWithId(TeleportationArrivalTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val procData = procStateData(info.data.currentRoute, tick)
      agentSimEventsBus.publish(MatsimEvent(PathTraversalEvent(tick, id, procData.nextLeg.graphPath, procData.nextLeg.mode)))
      val arrivalTime = teleportWalkDuration + tick
      agentSimEventsBus.publish(MatsimEvent(new PersonArrivalEvent(arrivalTime, id, info.data.nextActivity.right.get.getLinkId, TransportMode.walk)))
      val nextAct = info.data.nextActivity.right.get // No danger of failure here
      agentSimEventsBus.publish(MatsimEvent(new ActivityStartEvent(arrivalTime, id, nextAct.getLinkId, nextAct.getFacilityId, nextAct.getType)))
      logInfo(s"arrived at ${nextAct.getType} at $arrivalTime")
      // Agent should arrive before next activity ends, schedule trigger accordingly
      val actEndTriggerTime = Math.max(tick + minActDuration, nextAct.getEndTime)
      schedulerRef ! completed(triggerId, schedule[ActivityEndTrigger](actEndTriggerTime))
      goto(PerformingActivity) using stateData.copy(id, info.data.copy(currentActivityIndex = info.data.currentActivityIndex + 1))
  }

  // Driving-related states

  when(Driving) {
    case Event(TriggerWithId(PersonLeavesVehicleTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val procData = procStateData(info.data.currentRoute, tick)
      publishPathTraversal(PathTraversalEvent(tick, id, procData.nextLeg.graphPath, procData.nextLeg.mode))
      agentSimEventsBus.publish(MatsimEvent(new PersonLeavesVehicleEvent(procData.nextStart, id, Id.createVehicleId(s"car_$id"))))
      agentSimEventsBus.publish(MatsimEvent(new PersonArrivalEvent(procData.nextStart, id, info.data.nextActivity.right.get.getLinkId, TransportMode.car)))
      goto(Walking) using BeamAgentInfo(id, stateData.data.copy(currentRoute = procData.restTrip)) replying
        completed(triggerId, schedule[TeleportationArrivalTrigger](procData.nextStart))
  }

  // Taxi-related states

  when(InTaxi) {
    case Event(TriggerWithId(PersonLeavesTaxiTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val procData = procStateData(info.data.currentRoute, tick)
      publishPathTraversal(PathTraversalEvent(tick, id, procData.nextLeg.graphPath, procData.nextLeg.mode))
      agentSimEventsBus.publish(MatsimEvent(new PersonLeavesVehicleEvent(procData.nextStart, id, Id.createVehicleId(s"car_$id"))))
      agentSimEventsBus.publish(MatsimEvent(new PersonArrivalEvent(procData.nextStart, id, info.data.nextActivity.right.get.getLinkId, TransportMode.car)))
      info.data.currentVehicle.get ! DropOffCustomer(procData.nextLeg.graphPath.latLons.get.head)
      goto(Walking) using BeamAgentInfo(id, stateData.data.copy(currentRoute = procData.restTrip)) replying
        completed(triggerId, schedule[TeleportationArrivalTrigger](procData.nextStart))
  }

  // Transit-related states

  when(Waiting) {
    case Event(TriggerWithId(PersonEntersBoardingQueueTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val procData = procStateData(info.data.currentRoute, tick)
      publishPathTraversal(PathTraversalEvent(tick, id, procData.nextLeg.graphPath, procData.nextLeg.mode))
      agentSimEventsBus.publish(MatsimEvent(new AgentWaitingForPtEvent(tick, id, Id.create(Random.nextInt(), classOf[TransitStopFacility]), Id.create(Random.nextInt(), classOf[TransitStopFacility]))))
      goto(Boarding) using stateData.copy(id, info.data.copy(currentRoute = procData.restTrip)) replying
        completed(triggerId, schedule[PersonArrivesTransitStopTrigger](procData.nextStart))
  }

  when(Boarding) {
    case Event(TriggerWithId(PersonArrivesTransitStopTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val procData = procStateData(info.data.currentRoute, tick)
      publishPathTraversal(PathTraversalEvent(tick, id, procData.nextLeg.graphPath, procData.nextLeg.mode))
      agentSimEventsBus.publish(MatsimEvent(new PersonEntersVehicleEvent(tick, id, Id.createVehicleId(s"pt_$id"))))
      goto(OnTransit) using stateData.copy(id, info.data.copy(currentRoute = procData.restTrip)) replying
        completed(triggerId, schedule[PersonEntersAlightingQueueTrigger](procData.nextStart))
  }

  when(OnTransit) {
    case Event(TriggerWithId(PersonEntersAlightingQueueTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val procData = procStateData(info.data.currentRoute, tick)
      publishPathTraversal(PathTraversalEvent(tick, id, procData.nextLeg.graphPath, procData.nextLeg.mode))
      goto(Alighting) using stateData.copy(id, info.data.copy(currentRoute = procData.restTrip)) replying
        completed(triggerId, schedule[PersonLeavesVehicleTrigger](procData.nextStart))
  }

  when(Alighting) {
    case Event(TriggerWithId(PersonLeavesVehicleTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val procData = procStateData(info.data.currentRoute, tick)
      agentSimEventsBus.publish(MatsimEvent(new PersonLeavesVehicleEvent(tick, id, Id.createVehicleId(s"pt_$id"))))
      publishPathTraversal(PathTraversalEvent(tick, id, procData.nextLeg.graphPath, procData.nextLeg.mode))
      val restTrip = procData.restTrip

      // If there are remaining legs in transit trip (Transfers)
      restTrip.legs.headOption match {
        case Some(BeamLeg(_, "WALK", _, _)) if restTrip.legs.length == 1 =>
          agentSimEventsBus.publish(MatsimEvent(new PersonArrivalEvent(tick, id, info.data.nextActivity.right.get.getLinkId, TransportMode.pt)))
          goto(Walking) using stateData.copy(id, info.data.copy(currentRoute = restTrip)) replying
            completed(triggerId, schedule[TeleportationArrivalTrigger](procData.nextStart))
        case Some(BeamLeg(_, "WALK", _, _)) if restTrip.legs.length > 1 =>
          goto(Walking) using BeamAgentInfo(id, stateData.data.copy(currentRoute = restTrip)) replying // walk to different stop
            completed(triggerId, schedule[PersonArrivesTransitStopTrigger](procData.nextStart))
        case Some(BeamLeg(_, "WAITING", _, _)) =>
          goto(Waiting) using BeamAgentInfo(id, stateData.data.copy(currentRoute = restTrip)) replying
            completed(triggerId, schedule[PersonEntersBoardingQueueTrigger](procData.nextStart))
        case Some(BeamLeg(_, _, _, _)) => // Not sure if this is a good idea
          goto(Walking) using stateData.copy(id, info.data.copy(currentRoute = restTrip)) replying //
            completed(triggerId, schedule[TeleportationArrivalTrigger](procData.nextStart))
        case None =>
          logError(s"going to Error on trigger $triggerId in ALIGHTING")
          goto(Error) using stateData.copy(id, stateData.data.copy()) replying CompletionNotice(triggerId)
      }
  }


  onTransition {
    case Uninitialized -> Initialized =>
      registry ! Registry.Tell("scheduler", ScheduleTrigger(ActivityStartTrigger(0.0), self))
    case PerformingActivity -> ChoosingMode =>
      logInfo(s"going from PerformingActivity to ChoosingMode")
    case ChoosingMode -> Walking =>
      logInfo(s"going from ChoosingMode to Walking")
    case Walking -> Driving =>
      logInfo(s"going from Walking to Driving")
    case Driving -> Walking =>
      logInfo(s"going from Driving to Walking")
    case Walking -> PerformingActivity =>
      logInfo(s"going from Walking to PerformingActivity")
  }

  /*
   * Helper methods
   */
  def logInfo(msg: String): Unit = {
    //    log.info(s"PersonAgent $id: $msg")
  }

  def logWarn(msg: String): Unit = {
    log.warning(s"PersonAgent $id: $msg")
  }

  def logError(msg: String): Unit = {
    log.error(s"PersonAgent $id: $msg")
  }

  // NEVER use stateData in below, pass `info` object directly (closure around stateData on object creation)

  // TODO: Use shapeless Hlist/Generics (if Triggers only have double field) or roll own method to accept multiple triggers.
  def schedule[T <: Trigger](tick: Double)(implicit tag: scala.reflect.ClassTag[T]): Vector[ScheduleTrigger] = {
    Vector[ScheduleTrigger](ScheduleTrigger(tag.runtimeClass.getConstructor(classOf[Double]).newInstance(new java.lang.Double(tick)).asInstanceOf[T], self))
  }

  def completed(triggerId: Long, scheduleTriggers: Vector[ScheduleTrigger]): CompletionNotice = {
    CompletionNotice(triggerId, scheduleTriggers)
  }

  private def procStateData(trip: BeamTrip, tick: Double): ProcessedData = {

    val nextLeg: BeamLeg = trip.legs.head
    val restTrip: BeamTrip = BeamTrip(trip.legs.tail)
    val nextStart = if (restTrip.legs.nonEmpty) {
      restTrip.legs.head.startTime
    } else {
      tick
    }
    ProcessedData(nextLeg, restTrip, nextStart)
  }

  case class ProcessedData(nextLeg: BeamLeg, restTrip: BeamTrip, nextStart: Double)

  private def publishPathTraversal(event: PathTraversalEvent): Unit = {
    if (beamConfig.beam.events.pathTraversalEvents contains event.mode) {
      agentSimEventsBus.publish(MatsimEvent(event))

    }
  }

}


