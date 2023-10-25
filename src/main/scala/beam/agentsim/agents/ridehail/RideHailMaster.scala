package beam.agentsim.agents.ridehail

import akka.actor.{ActorRef, Props, Terminated}
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.agents.choice.logit.{MultinomialLogit, UtilityFunctionOperation}
import beam.agentsim.agents.ridehail.RideHailManager.ResponseCache
import beam.agentsim.agents.ridehail.RideHailManager.TravelProposal
import beam.agentsim.agents.ridehail.RideHailMaster.RequestWithResponses
import beam.agentsim.agents.vehicles.AccessErrorCodes.{DriverNotFoundError, UnknownInquiryIdError}
import beam.agentsim.agents.vehicles.{PersonIdWithActorRef, VehicleManager}
import beam.sim.population.AttributesOfIndividual
import beam.sim.population.PopulationAdjustment._
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.Modes.BeamMode.{RIDE_HAIL, RIDE_HAIL_POOLED}
import beam.router.RouteHistory
import beam.router.osm.TollCalculator
import beam.sim.{BeamScenario, BeamServices, RideHailFleetInitializerProvider}
import beam.utils.logging.LoggingMessageActor
import beam.utils.matsim_conversion.ShapeUtils.QuadTreeBounds
import com.conveyal.r5.transit.TransportNetwork
import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager

import scala.collection.mutable
import scala.util.Random

/**
  * @author Dmitry Openkov
  */
class RideHailMaster(
  val beamServices: BeamServices,
  val beamScenario: BeamScenario,
  val transportNetwork: TransportNetwork,
  val tollCalculator: TollCalculator,
  val scenario: Scenario,
  val eventsManager: EventsManager,
  val scheduler: ActorRef,
  val router: ActorRef,
  val parkingManager: ActorRef,
  val chargingNetworkManager: ActorRef,
  val boundingBox: Envelope,
  val activityQuadTreeBounds: QuadTreeBounds,
  val surgePricingManager: RideHailSurgePricingManager,
  val tncIterationStats: Option[TNCIterationStats],
  val routeHistory: RouteHistory,
  val rideHailFleetInitializerProvider: RideHailFleetInitializerProvider
) extends LoggingMessageActor
    with LazyLogging {

  private val rideHailManagers: Map[String, ActorRef] =
    beamServices.beamConfig.beam.agentsim.agents.rideHail.managers.map { managerConfig =>
      val rideHailManagerId =
        VehicleManager.createOrGetReservedFor(managerConfig.name, VehicleManager.TypeEnum.RideHail).managerId
      val rideHailFleetInitializer = rideHailFleetInitializerProvider.get(managerConfig.name)
      managerConfig.name -> context.actorOf(
        Props(
          new RideHailManager(
            rideHailManagerId,
            beamServices,
            beamScenario,
            beamScenario.transportNetwork,
            tollCalculator,
            scenario,
            eventsManager,
            scheduler,
            router,
            parkingManager,
            chargingNetworkManager,
            boundingBox,
            activityQuadTreeBounds,
            surgePricingManager,
            tncIterationStats,
            routeHistory,
            rideHailFleetInitializer,
            managerConfig
          )
        ).withDispatcher("ride-hail-manager-pinned-dispatcher"),
        s"RideHailManager-${managerConfig.name}"
      )
    }.toMap

  for (rhm <- rideHailManagers.values) context.watch(rhm)

  private val inquiriesWithResponses: mutable.Map[Int, RequestWithResponses] = mutable.Map.empty
  private val rideHailResponseCache = new ResponseCache
  val rand: Random = new Random(beamScenario.beamConfig.matsim.modules.global.randomSeed)
  private val bestResponseType: String = beamServices.beamConfig.beam.agentsim.agents.rideHail.bestResponseType

  override def loggedReceive: Receive = {
    case TriggerWithId(trigger: InitializeTrigger, triggerId) =>
      sender ! CompletionNotice(triggerId, rideHailManagers.values.map(rhm => ScheduleTrigger(trigger, rhm)).toVector)

    case inquiry: RideHailRequest if !inquiry.shouldReserveRide =>
      inquiriesWithResponses.put(inquiry.requestId, RequestWithResponses(inquiry))
      val requestWithModifiedRequester = inquiry.copy(requester = self)
      getCustomerRideHailManagers(inquiry.rideHailServiceSubscription).foreach(_ ! requestWithModifiedRequester)

    case rideHailResponse: RideHailResponse if !rideHailResponse.request.shouldReserveRide =>
      val requestId = rideHailResponse.request.requestId
      val requestWithResponses: RequestWithResponses = inquiriesWithResponses(requestId)
      val newRequestWithResponses = requestWithResponses.addResponse(rideHailResponse)
      val customerRHMs = getCustomerRideHailManagers(requestWithResponses.request.rideHailServiceSubscription)
      if (newRequestWithResponses.responses.size == customerRHMs.size) {
        inquiriesWithResponses.remove(requestId)
        val bestResponse: RideHailResponse =
          findBestProposal(requestWithResponses.request.customer.personId, newRequestWithResponses.responses)
        rideHailResponseCache.add(bestResponse)
        newRequestWithResponses.request.customer.personRef ! bestResponse
      } else {
        inquiriesWithResponses.update(requestId, newRequestWithResponses)
      }

    case reserveRide: RideHailRequest if reserveRide.shouldReserveRide =>
      //in case of ReserveRide type requester equals customer.personRef
      rideHailResponseCache.removeOriginalResponseFromCache(reserveRide) match {
        case Some(originalResponse) =>
          rideHailManagers(originalResponse.rideHailManagerName) forward reserveRide
        case None =>
          logger.error(s"Cannot find originalResponse for $reserveRide")
          sender() ! RideHailResponse.dummyWithError(UnknownInquiryIdError)
      }

    case Finish =>
      rideHailResponseCache.clear()
      rideHailManagers.values.foreach(_ ! Finish)

    case _: Terminated =>
      if (context.children.isEmpty) context.stop(self)

    case anyOtherMessage =>
      rideHailManagers.values.foreach(_.forward(anyOtherMessage))
  }

  private def getCustomerRideHailManagers(subscription: Seq[String]): Iterable[ActorRef] = {
    val subscribedTo = subscription.collect(rideHailManagers)
    if (subscribedTo.isEmpty) rideHailManagers.values else subscribedTo
  }

  private def findBestProposal(customer: Id[Person], responses: IndexedSeq[RideHailResponse]): RideHailResponse = {
    val responsesInRandomOrder = rand.shuffle(responses)
    val request = responsesInRandomOrder.head.request
    // asPooled is set to false only in case person's current tour mode is RIDE_HAIL
    // FIXME refactoring: we could use possibleModes: Set[BeamMode] instead of asPooled in RH request.
    val customerHasRequestedSoloTrip = !request.asPooled
    val availableProposals =
      if (customerHasRequestedSoloTrip)
        responsesInRandomOrder.filter(_.travelProposal.exists(_.modeOptions.contains(RIDE_HAIL)))
      else
        responsesInRandomOrder.filter(_.travelProposal.isDefined)
    if (availableProposals.isEmpty) {
      logger.warn(f"Can't find a driver because no available proposals for request ${request.toString}")
      RideHailResponse.dummyWithError(DriverNotFoundError, request)
    } else
      bestResponseType match {
        case "MIN_COST"    => availableProposals.minBy(findCost(customer, _))
        case "MIN_UTILITY" => sampleProposals(customer, availableProposals)
      }
  }

  private def sampleProposals(customer: Id[Person], responses: IndexedSeq[RideHailResponse]): RideHailResponse = {
    val proposalsToSample: Map[RideHailResponse, Map[String, Double]] =
      proposalsToResponseAlternatives(customer, responses)
    val mnlParams = Map(
      "cost"         -> UtilityFunctionOperation.Multiplier(-1.0),
      "subscription" -> UtilityFunctionOperation.Multiplier(1.0)
    )
    val mnl: MultinomialLogit[RideHailResponse, String] = MultinomialLogit(Map.empty, mnlParams)
    val proposalsWithUtility = mnl.calcAlternativesWithUtility(proposalsToSample)
    val chosenProposal = mnl.sampleAlternative(proposalsWithUtility, rand)
    chosenProposal.get.alternativeType
  }

  private def proposalsToResponseAlternatives(
    customer: Id[Person],
    responses: IndexedSeq[RideHailResponse]
  ): Map[RideHailResponse, Map[String, Double]] = {
    val person = beamServices.matsimServices.getScenario.getPopulation.getPersons.get(customer)
    val customerAttributes = person.getCustomAttributes.get(BEAM_ATTRIBUTES).asInstanceOf[AttributesOfIndividual]
    responses.map { alt =>
      val cost: Double = findCost(customer, alt)
      val scaledTime: Double = customerAttributes.getVOT(
        getGeneralizedTimeOfProposalInHours(alt.request.customer, alt.travelProposal)
      )
      val hasSubscription =
        if (alt.request.rideHailServiceSubscription.contains(alt.rideHailManagerName)) 1.0 else 0.0

      alt ->
      Map(
        "cost"         -> (cost + scaledTime),
        "subscription" -> hasSubscription * beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.multinomialLogit.params.ride_hail_subscription
      )

    }.toMap
  }

  private def findCost(customer: Id[Person], response: RideHailResponse): Double = {
    val travelProposal = response.travelProposal.get
    val price = travelProposal.estimatedPrice(customer)
    if (
      travelProposal.modeOptions.contains(RIDE_HAIL_POOLED)
      && travelProposal.poolingInfo.isDefined
      && response.request.asPooled
    ) // pooling is supported by RHM and person has choice to pick pooled as not assigned some other tour mode
      Math.min(price, price * travelProposal.poolingInfo.get.costFactor)
    else
      price
  }

  private def getGeneralizedTimeOfProposalInHours(
    passenger: PersonIdWithActorRef,
    proposal: Option[TravelProposal]
  ): Double = {
    // TODO: add walking time once walk-to-point service is implemented
    proposal match {
      case Some(proposal) =>
        val wait = proposal.maxWaitingTimeInSec
        val duration = proposal.travelTimeForCustomer(passenger)
        (duration + (wait * beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.modeVotMultiplier.waiting)) / 3600
      case _ => 0.0
    }

  }
}

object RideHailMaster {

  case class RequestWithResponses(
    request: RideHailRequest,
    responses: IndexedSeq[RideHailResponse] = IndexedSeq.empty
  ) {

    def addResponse(response: RideHailResponse): RequestWithResponses =
      this.copy(responses = this.responses :+ response)
  }
}
