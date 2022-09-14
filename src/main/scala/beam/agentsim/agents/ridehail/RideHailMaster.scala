package beam.agentsim.agents.ridehail

import akka.actor.{ActorRef, Props, Terminated}
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.agents.ridehail.RideHailManager.{
  BufferedRideHailRequestsTrigger,
  ResponseCache,
  RideHailRepositioningTrigger
}
import beam.agentsim.agents.ridehail.RideHailMaster.RequestWithResponses
import beam.agentsim.agents.vehicles.AccessErrorCodes.UnknownInquiryIdError
import beam.agentsim.agents.vehicles.{PersonIdWithActorRef, VehicleManager}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.RouteHistory
import beam.router.osm.TollCalculator
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents.RideHail.Managers$Elm
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

  rideHailManagers.foreach { case (rhmName, rideHailManager) =>
    val managerConfig = beamServices.beamConfig.beam.agentsim.agents.rideHail.managers.find(_.name == rhmName).get
    scheduleRideHailManagerTimerMessages(managerConfig, rideHailManager)
  }

  private val inquiriesWithResponses: mutable.Map[Int, RequestWithResponses] = mutable.Map.empty
  private val rideHailResponseCache = new ResponseCache

  override def loggedReceive: Receive = {
    case TriggerWithId(trigger: InitializeTrigger, triggerId) =>
      sender ! CompletionNotice(triggerId, rideHailManagers.values.map(rhm => ScheduleTrigger(trigger, rhm)).toVector)

    case inquiry: RideHailRequest if inquiry.requestType == RideHailInquiry =>
      inquiriesWithResponses.put(inquiry.requestId, RequestWithResponses(inquiry))
      val requestWithModifiedRequester = inquiry.copy(requester = self)
      rideHailManagers.values.foreach(_ ! requestWithModifiedRequester)

    case rideHailResponse: RideHailResponse if rideHailResponse.request.requestType == RideHailInquiry =>
      val requestId = rideHailResponse.request.requestId
      val requestWithResponses: RequestWithResponses = inquiriesWithResponses(requestId)
      val allData = requestWithResponses.addResponse(rideHailResponse)
      if (allData.responses.size == rideHailManagers.size) {
        inquiriesWithResponses.remove(requestId)
        val withProposals = allData.responses.filter(_.travelProposal.isDefined)
        val bestResponse =
          if (withProposals.isEmpty) allData.responses.head
          else withProposals.minBy(_.travelProposal.get.estimatedPrice(rideHailResponse.request.customer.personId))
        rideHailResponseCache.add(bestResponse)
        allData.request.customer.personRef ! bestResponse
      } else {
        inquiriesWithResponses.update(requestId, allData)
      }

    case reserveRide: RideHailRequest if reserveRide.requestType == ReserveRide =>
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

  private def scheduleRideHailManagerTimerMessages(managerConfig: Managers$Elm, rideHailManager: ActorRef): Unit = {
    if (managerConfig.repositioningManager.timeout > 0) {
      // We need to stagger init tick for repositioning manager and allocation manager
      // This is important because during the `requestBufferTimeoutInSeconds` repositioned vehicle is not available, so to make them work together
      // we have to make sure that there is no overlap
      val initTick = managerConfig.repositioningManager.timeout / 2
      scheduler ! ScheduleTrigger(RideHailRepositioningTrigger(initTick), rideHailManager)
    }
    if (managerConfig.allocationManager.requestBufferTimeoutInSeconds > 0)
      scheduler ! ScheduleTrigger(BufferedRideHailRequestsTrigger(0), rideHailManager)
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
