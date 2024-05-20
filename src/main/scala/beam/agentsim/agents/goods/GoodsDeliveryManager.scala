package beam.agentsim.agents.goods

import akka.actor.{ActorLogging, ActorRef, Props}
import akka.util.Timeout
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.agents.freight.{FreightRequestType, PayloadPlan}
import beam.agentsim.agents.goods.GoodsDeliveryManager.{GOODS_PREFIX, GoodsDeliveryTrigger}
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.{AlightVehicleTrigger, BoardVehicleTrigger}
import beam.agentsim.agents.ridehail._
import beam.agentsim.agents.vehicles.AccessErrorCodes.UnknownInquiryIdError
import beam.agentsim.agents.vehicles.PersonIdWithActorRef
import beam.agentsim.events.RideHailReservationConfirmationEvent.{Pooled, Solo}
import beam.agentsim.events.resources.ReservationError
import beam.agentsim.events.{BeamPersonDepartureEvent, ReserveRideHailEvent, RideHailReservationConfirmationEvent}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.Modes.BeamMode
import beam.router.model.BeamLeg
import beam.router.skim.event.{RideHailSkimmerEvent, UnmatchedRideHailRequestSkimmerEvent}
import beam.sim.{BeamScenario, BeamServices}
import beam.utils.MeasureUnitConversion.METERS_IN_MILE
import beam.utils.logging.LoggingMessageActor
import beam.utils.matsim_conversion.MatsimPlanConversion.IdOps
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.{PersonArrivalEvent, PersonEntersVehicleEvent, PersonLeavesVehicleEvent}
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.population.Person
import org.matsim.core.api.experimental.events.EventsManager

import java.util.concurrent.TimeUnit

private class GoodsDeliveryManager(
  val beamScenario: BeamScenario,
  val beamServices: BeamServices,
  val scheduler: ActorRef,
  val rideHailManager: ActorRef,
  val eventsManager: EventsManager
) extends LoggingMessageActor
    with ActorLogging {

  override def loggedReceive: PartialFunction[Any, Unit] = { case TriggerWithId(InitializeTrigger(_), triggerId) =>
    implicit val _: Timeout = Timeout(120, TimeUnit.SECONDS)
    for {
      carrier <- beamScenario.goodsCarriers
      tours   <- carrier.tourMap.values
      tour    <- tours
      plans = carrier.plansPerTour(tour.tourId)
    } {
      val (pickups, destinations) = plans.span(plan => plan.requestType == FreightRequestType.Loading)
      if (pickups.size != 1)
        throw new IllegalArgumentException(
          s"Number of loading types must be one. ${tour.tourId} has ${pickups.size} ones. "
        )
      if (destinations.isEmpty)
        throw new IllegalArgumentException(
          s"A Tour must have at least one unloading. ${tour.tourId} has no unloading. "
        )
      val pickup = pickups.head
      val requestTime = pickup.estimatedTimeOfArrivalInSec - 200
      destinations.foreach(destination =>
        scheduler ! ScheduleTrigger(GoodsDeliveryTrigger(requestTime, pickup, destination), self)
      )
    }
    scheduler ! CompletionNotice(triggerId)
    contextBecome(operate(Map.empty))
  }

  private def operate(goodsData: Map[Id[Person], Id[Link]]): Receive = {
    case TriggerWithId(GoodsDeliveryTrigger(currentTick, pickup, destination), triggerId) =>
      val virtualPersonId = (GOODS_PREFIX + destination.payloadId.toString).createId[Person]
      rideHailManager ! RideHailRequest(
        ReserveRide("GlobalRHM"),
        PersonIdWithActorRef(virtualPersonId, self),
        pickup.locationUTM,
        pickup.estimatedTimeOfArrivalInSec,
        destination.locationUTM,
        asPooled = true,
        requestTime = currentTick,
        quotedWaitTime = None,
        requester = self,
        rideHailServiceSubscription = Seq.empty,
        triggerId = triggerId
      )

      eventsManager.processEvent(
        new ReserveRideHailEvent(
          currentTick.toDouble,
          virtualPersonId,
          pickup.estimatedTimeOfArrivalInSec,
          pickup.locationUTM,
          destination.locationUTM,
          false
        )
      )
    // RIDE HAIL DELAY
    // this means ride hail manager is taking time to assign and we should complete our
    // current trigger and wait to be re-triggered by the manager
    case DelayedRideHailResponse(triggerId) =>
      scheduler ! CompletionNotice(triggerId, Vector())
    // RIDE HAIL DELAY SUCCESS (buffered mode of RHM)
    case TriggerWithId(RideHailResponseTrigger(tick, response: RideHailResponse), triggerId)
        if response.isSuccessful(response.request.customer.personId) =>
      val legs = generateSuccessfulRideHailEvents(tick, response, response.request.customer)
      val arrivalLink = Id.createLinkId(legs.last.travelPath.linkIds.last)
      scheduler ! CompletionNotice(triggerId, newTriggers = response.triggersToSchedule)
      context.become(operate(goodsData + (response.request.customer.personId -> arrivalLink)))
    // RIDE HAIL DELAY FAILURE
    case TriggerWithId(RideHailResponseTrigger(tick, response: RideHailResponse), triggerId) =>
      generateFailedRideHailEvents(
        tick,
        response.error.getOrElse(UnknownInquiryIdError),
        response,
        response.request.customer
      )
      scheduler ! CompletionNotice(triggerId)
    // RIDE HAIL SUCCESS (single request mode of RHM)
    case response: RideHailResponse if response.isSuccessful(response.request.customer.personId) =>
      val legs = generateSuccessfulRideHailEvents(response.request.requestTime, response, response.request.customer)
      val arrivalLink = Id.createLinkId(legs.last.travelPath.linkIds.last)
      context.become(operate(goodsData + (response.request.customer.personId -> arrivalLink)))
    // RIDE HAIL FAILURE (single request mode of RHM)
    case response: RideHailResponse =>
      generateFailedRideHailEvents(
        response.request.requestTime,
        response.error.getOrElse(UnknownInquiryIdError),
        response,
        response.request.customer
      )
    case TriggerWithId(BoardVehicleTrigger(tick, vehicleToEnter, passenger), triggerId) =>
      eventsManager.processEvent(new PersonEntersVehicleEvent(tick, passenger.get.personId, vehicleToEnter))
      scheduler ! CompletionNotice(triggerId)
    case TriggerWithId(AlightVehicleTrigger(tick, vehicleToExit, passenger, _), triggerId) =>
      val packageId = passenger.get.personId
      val linkId = goodsData(packageId)
      eventsManager.processEvent(new PersonLeavesVehicleEvent(tick, packageId, vehicleToExit))
      eventsManager.processEvent(new PersonArrivalEvent(tick, packageId, linkId, BeamMode.RIDE_HAIL.value))
      scheduler ! CompletionNotice(triggerId)
      context.become(operate(goodsData - packageId))
    case Finish =>
      context.stop(self)
  }

  private def generateSuccessfulRideHailEvents(
    tick: Int,
    response: RideHailResponse,
    passenger: PersonIdWithActorRef
  ): IndexedSeq[BeamLeg] = {
    val req = response.request
    val travelProposal = response.travelProposal.get
    val actualRideHailLegs = travelProposal.passengerSchedule.legsWithPassenger(passenger)
    eventsManager.processEvent(
      new RideHailReservationConfirmationEvent(
        tick,
        passenger.personId,
        Some(travelProposal.rideHailAgentLocation.vehicleId),
        RideHailReservationConfirmationEvent.typeWhenPooledIs(req.asPooled),
        None,
        tick,
        req.departAt,
        req.quotedWaitTime,
        beamServices.geo.utm2Wgs(req.pickUpLocationUTM),
        beamServices.geo.utm2Wgs(req.destinationUTM),
        Some(actualRideHailLegs.head.startTime),
        response.directTripTravelProposal.map(_.travelDistanceForCustomer(passenger)),
        response.directTripTravelProposal.map(_.travelTimeForCustomer(passenger)),
        Some(travelProposal.estimatedPrice(req.customer.personId)),
        req.withWheelchair
      )
    )
    eventsManager.processEvent(
      new RideHailSkimmerEvent(
        eventTime = tick,
        tazId = beamScenario.tazTreeMap.getTAZ(req.pickUpLocationUTM).tazId,
        reservationType = if (req.asPooled) Pooled else Solo,
        serviceName = response.rideHailManagerName,
        waitTime = travelProposal.timeToCustomer(req.customer),
        costPerMile = travelProposal.estimatedPrice(req.customer.personId) /
          travelProposal.travelDistanceForCustomer(req.customer) * METERS_IN_MILE,
        wheelchairRequired = req.withWheelchair,
        vehicleIsWheelchairAccessible = travelProposal.rideHailAgentLocation.vehicleType.isWheelchairAccessible
      )
    )
    eventsManager.processEvent(
      new BeamPersonDepartureEvent(
        actualRideHailLegs.head.startTime,
        passenger.personId,
        Id.createLinkId(actualRideHailLegs.head.travelPath.linkIds.head),
        BeamMode.RIDE_HAIL.value,
        passenger.personId.toString
      )
    )
    actualRideHailLegs
  }

  private def generateFailedRideHailEvents(
    tick: Int,
    error: ReservationError,
    response: RideHailResponse,
    passenger: PersonIdWithActorRef
  ): Unit = {
    eventsManager.processEvent(
      new RideHailReservationConfirmationEvent(
        tick,
        passenger.personId,
        None,
        RideHailReservationConfirmationEvent.typeWhenPooledIs(response.request.asPooled),
        Some(error.errorCode),
        response.request.requestTime,
        response.request.departAt,
        response.request.quotedWaitTime,
        beamServices.geo.utm2Wgs(response.request.pickUpLocationUTM),
        beamServices.geo.utm2Wgs(response.request.destinationUTM),
        None,
        response.directTripTravelProposal.map(_.travelDistanceForCustomer(passenger)),
        response.directTripTravelProposal.map(proposal =>
          proposal.travelTimeForCustomer(passenger) + proposal.timeToCustomer(passenger)
        ),
        None,
        response.request.withWheelchair
      )
    )
    eventsManager.processEvent(
      new UnmatchedRideHailRequestSkimmerEvent(
        eventTime = tick,
        tazId = beamScenario.tazTreeMap.getTAZ(response.request.pickUpLocationUTM).tazId,
        reservationType = if (response.request.asPooled) Pooled else Solo,
        wheelchairRequired = response.request.withWheelchair,
        serviceName = response.rideHailManagerName
      )
    )
  }

}

object GoodsDeliveryManager {
  val GOODS_PREFIX = "goods-"

  case class GoodsDeliveryTrigger(tick: Int, pickup: PayloadPlan, destinations: PayloadPlan) extends Trigger

  def props(
    beamScenario: BeamScenario,
    services: BeamServices,
    scheduler: ActorRef,
    rideHailManager: ActorRef,
    eventsManager: EventsManager
  ): Props = {
    Props(
      new GoodsDeliveryManager(
        beamScenario,
        services,
        scheduler,
        rideHailManager,
        eventsManager
      )
    )
  }
}
