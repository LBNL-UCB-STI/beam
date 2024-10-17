package beam.agentsim.agents.goods

import akka.actor.{ActorLogging, ActorRef, Props}
import akka.util.Timeout
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.agents.choice.mode.ModeChoiceRideHailIfAvailable
import beam.agentsim.agents.freight.input.FreightReader.NO_CARRIER_ID
import beam.agentsim.agents.freight.{FreightRequestType, PayloadPlan}
import beam.agentsim.agents.goods.GoodsDeliveryManager.{GOODS_PREFIX, GoodsDeliveryTrigger}
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.{AlightVehicleTrigger, BoardVehicleTrigger}
import beam.agentsim.agents.ridehail._
import beam.agentsim.agents.vehicles.AccessErrorCodes.UnknownInquiryIdError
import beam.agentsim.agents.vehicles.BeamVehicle.FuelConsumed
import beam.agentsim.agents.vehicles.PersonIdWithActorRef
import beam.agentsim.events.RideHailReservationConfirmationEvent.{Pooled, Solo}
import beam.agentsim.events.resources.ReservationError
import beam.agentsim.events.{BeamPersonDepartureEvent, ReserveRideHailEvent, RideHailReservationConfirmationEvent}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.Modes.BeamMode
import beam.router.model.{EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.skim.event.{
  ODSkimmerEvent,
  ODVehicleTypeSkimmerEvent,
  RideHailSkimmerEvent,
  UnmatchedRideHailRequestSkimmerEvent
}
import beam.sim.{BeamScenario, BeamServices}
import beam.utils.MathUtils
import beam.utils.MeasureUnitConversion.METERS_IN_MILE
import beam.utils.logging.LoggingMessageActor
import beam.utils.matsim_conversion.MatsimPlanConversion.IdOps
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.{PersonArrivalEvent, PersonEntersVehicleEvent, PersonLeavesVehicleEvent}
import org.matsim.api.core.v01.population.Person
import org.matsim.core.api.experimental.events.EventsManager

import java.util.concurrent.TimeUnit
import scala.util.Random

private class GoodsDeliveryManager(
  val beamScenario: BeamScenario,
  val beamServices: BeamServices,
  val scheduler: ActorRef,
  val rideHailManager: ActorRef,
  val eventsManager: EventsManager
) extends LoggingMessageActor
    with ActorLogging {

  private val rand = new Random(beamScenario.beamConfig.matsim.modules.global.randomSeed)
  private val modeChoiceCalculator = new ModeChoiceRideHailIfAvailable(beamServices)

  private val goodsRhmNames: IndexedSeq[String] =
    beamServices.beamConfig.beam.agentsim.agents.rideHail.managers.collect {
      case managerConfig if RideHailManager.getSupportedModes(managerConfig.supportedModes)._2 => managerConfig.name
    }.toIndexedSeq

  override def loggedReceive: PartialFunction[Any, Unit] = { case TriggerWithId(InitializeTrigger(_), triggerId) =>
    implicit val _: Timeout = Timeout(120, TimeUnit.SECONDS)
    val triggers: IndexedSeq[ScheduleTrigger] = (for {
      carrier <- beamScenario.goodsCarriers
      tours   <- carrier.tourMap.values
      tour    <- tours
      plans = carrier.plansPerTour(tour.tourId)
    } yield {
      val (pickups, destinations) = plans.partition(plan => plan.requestType == FreightRequestType.Loading)
      if (pickups.size != 1)
        throw new IllegalArgumentException(
          s"Number of loading types must be one. ${tour.tourId} has ${pickups.size} ones. "
        )
      if (destinations.isEmpty)
        throw new IllegalArgumentException(
          s"A Tour must have at least one unloading. ${tour.tourId} has no unloading. "
        )
      val rhmName: Option[String] = if (carrier.carrierId == NO_CARRIER_ID) None else Some(carrier.carrierId.toString)
      val pickup = pickups.head
      val requestTime = pickup.estimatedTimeOfArrivalInSec - 200
      destinations.map(destination =>
        ScheduleTrigger(GoodsDeliveryTrigger(requestTime, rhmName, pickup, destination), self)
      )
    }).flatten
    if (triggers.nonEmpty && goodsRhmNames.isEmpty) {
      throw new IllegalArgumentException("No ride-hail managers that operates with goods")
    }
    triggers.foreach(scheduler ! _)
    scheduler ! CompletionNotice(triggerId)
    contextBecome(operate(Map.empty))
  }

  /**
    * When init steps are done and the simulation has started this one is our main actor Receive method.
    * @param goodsData keeps arrival link for each package in order to generate PersonArrivalEvent
    */
  private def operate(goodsData: Map[Id[Person], EmbodiedBeamTrip]): Receive = {
    case TriggerWithId(GoodsDeliveryTrigger(currentTick, rhmName, pickup, destination), triggerId) =>
      val virtualPersonId = (GOODS_PREFIX + destination.payloadId.toString).createId[Person]
      rideHailManager ! RideHailRequest(
        ReserveRide(rhmName.getOrElse(MathUtils.selectRandomElement(goodsRhmNames, rand))),
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
      val trip = generateSuccessfulRideHailEvents(tick, response, response.request.customer)
      scheduler ! CompletionNotice(triggerId, newTriggers = response.triggersToSchedule)
      context.become(operate(goodsData + (response.request.customer.personId -> trip)))
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
      val trip = generateSuccessfulRideHailEvents(response.request.requestTime, response, response.request.customer)
      context.become(operate(goodsData + (response.request.customer.personId -> trip)))
    // RIDE HAIL FAILURE (single request mode of RHM)
    case response: RideHailResponse =>
      generateFailedRideHailEvents(
        response.request.requestTime,
        response.error.getOrElse(UnknownInquiryIdError),
        response,
        response.request.customer
      )
    case TriggerWithId(BoardVehicleTrigger(tick, vehicleToEnter, passenger), triggerId) =>
      eventsManager.processEvent(new PersonEntersVehicleEvent(tick, passenger.personId, vehicleToEnter))
      scheduler ! CompletionNotice(triggerId)
    case TriggerWithId(AlightVehicleTrigger(tick, vehicleToExit, passenger, fuelConsumed), triggerId) =>
      val packageId = passenger.personId
      val trip = goodsData(packageId)
      val arrivalLink = Id.createLinkId(trip.legs.last.beamLeg.travelPath.linkIds.last)
      eventsManager.processEvent(new PersonLeavesVehicleEvent(tick, packageId, vehicleToExit))
      eventsManager.processEvent(new PersonArrivalEvent(tick, packageId, arrivalLink, BeamMode.RIDE_HAIL.value))
      val (odEvent, odVehicleTypeEvent) = generateODSkimEvents(tick, trip, fuelConsumed.get)
      eventsManager.processEvent(odEvent)
      eventsManager.processEvent(odVehicleTypeEvent)
      scheduler ! CompletionNotice(triggerId)
      context.become(operate(goodsData - packageId))
    case Finish =>
      context.stop(self)
  }

  private def generateODSkimEvents(
    tick: Int,
    trip: EmbodiedBeamTrip,
    fuelConsumed: FuelConsumed
  ): (ODSkimmerEvent, ODVehicleTypeSkimmerEvent) = {
    val generalizedTime = modeChoiceCalculator.getGeneralizedTimeOfTrip(trip)
    val generalizedCost = modeChoiceCalculator.getNonTimeCost(trip)
    val (odSkimmerEvent, _, _) = ODSkimmerEvent.forTaz(
      tick,
      beamServices,
      BeamMode.FREIGHT,
      trip,
      generalizedTime,
      generalizedCost,
      None,
      fuelConsumed.totalEnergyConsumed,
      failedTrip = false
    )
    val vehicleType = beamScenario.vehicleTypes(trip.legs.head.beamVehicleTypeId)

    val odVehicleTypeEvent = ODVehicleTypeSkimmerEvent(
      tick,
      beamServices,
      vehicleType,
      trip,
      generalizedTime,
      generalizedCost,
      None,
      fuelConsumed.totalEnergyConsumed
    )

    (odSkimmerEvent, odVehicleTypeEvent)
  }

  private def generateSuccessfulRideHailEvents(
    tick: Int,
    response: RideHailResponse,
    passenger: PersonIdWithActorRef
  ): EmbodiedBeamTrip = {
    val req = response.request
    val travelProposal = response.travelProposal.get
    val legs: IndexedSeq[EmbodiedBeamLeg] =
      travelProposal.toEmbodiedBeamLegsForCustomer(req.customer, response.rideHailManagerName)
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
        Some(legs.head.beamLeg.startTime),
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
        legs.head.beamLeg.startTime,
        passenger.personId,
        Id.createLinkId(legs.head.beamLeg.travelPath.linkIds.head),
        BeamMode.RIDE_HAIL.value,
        passenger.personId.toString
      )
    )
    EmbodiedBeamTrip(legs)
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

  case class GoodsDeliveryTrigger(
    tick: Int,
    rideHailManagerName: Option[String],
    pickup: PayloadPlan,
    destination: PayloadPlan
  ) extends Trigger

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
