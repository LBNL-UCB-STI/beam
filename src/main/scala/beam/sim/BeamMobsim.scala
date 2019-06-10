package beam.sim

import java.util.concurrent.TimeUnit

import akka.actor.Status.Success
import akka.actor.SupervisorStrategy.Stop
import akka.actor.{
  Actor,
  ActorLogging,
  ActorRef,
  ActorSystem,
  Cancellable,
  DeadLetter,
  OneForOneStrategy,
  Props,
  Terminated
}
import akka.pattern.ask
import akka.util.Timeout
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.ridehail.RideHailManager.{BufferedRideHailRequestsTrigger, RideHailRepositioningTrigger}
import beam.agentsim.agents.ridehail.{RideHailIterationHistory, RideHailManager, RideHailSurgePricingManager}
import beam.agentsim.agents.{BeamAgent, InitializeTrigger, Population, TransitSystem}
import beam.agentsim.infrastructure.ZonalParkingManager
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger, StartSchedule}
import beam.router._
import beam.router.osm.TollCalculator
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig.Beam
import beam.sim.metrics.{Metrics, MetricsSupport}
import beam.sim.monitoring.ErrorListener
import beam.sim.vehiclesharing.Fleets
import beam.utils._
import com.conveyal.r5.transit.TransportNetwork
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.mobsim.framework.Mobsim
import org.matsim.core.utils.misc.Time

import scala.concurrent.Await
import scala.concurrent.duration._

class BeamMobsim @Inject()(
  val beamServices: BeamServices,
  val beamScenario: BeamScenario,
  val transportNetwork: TransportNetwork,
  val tollCalculator: TollCalculator,
  val scenario: Scenario,
  val eventsManager: EventsManager,
  val actorSystem: ActorSystem,
  val rideHailSurgePricingManager: RideHailSurgePricingManager,
  val rideHailIterationHistory: RideHailIterationHistory,
  val routeHistory: RouteHistory,
  val beamSkimmer: BeamSkimmer,
  val travelTimeObserved: TravelTimeObserved,
  val geo: GeoUtils,
  val networkHelper: NetworkHelper
) extends Mobsim
    with LazyLogging
    with MetricsSupport {
  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)

  override def run(): Unit = {
    logger.info("Starting Iteration")
    startMeasuringIteration(beamServices.matsimServices.getIterationNumber)
    logger.info("Preparing new Iteration (Start)")
    startSegment("iteration-preparation", "mobsim")

    if (beamServices.beamConfig.beam.debug.debugEnabled)
      logger.info(DebugLib.gcAndGetMemoryLogMessage("run.start (after GC): "))
    Metrics.iterationNumber = beamServices.matsimServices.getIterationNumber
    eventsManager.initProcessing()

    val iteration = actorSystem.actorOf(
      Props(
        new BeamMobsimIteration(
          beamServices,
          rideHailSurgePricingManager,
          rideHailIterationHistory,
          routeHistory,
          beamSkimmer,
          travelTimeObserved
        )
      ),
      "BeamMobsim.iteration"
    )
    Await.result(iteration ? "Run!", timeout.duration)

    logger.info("Agentsim finished.")
    eventsManager.finishProcessing()
    logger.info("Events drained.")
    endSegment("agentsim-events", "agentsim")

    logger.info("Processing Agentsim Events (End)")
  }

}

class BeamMobsimIteration(
  val beamServices: BeamServices,
  val rideHailSurgePricingManager: RideHailSurgePricingManager,
  val rideHailIterationHistory: RideHailIterationHistory,
  val routeHistory: RouteHistory,
  val beamSkimmer: BeamSkimmer,
  val travelTimeObserved: TravelTimeObserved
) extends Actor
    with ActorLogging
    with MetricsSupport {

  override val supervisorStrategy: OneForOneStrategy =
    OneForOneStrategy(maxNrOfRetries = 0) {
      case _: Exception      => Stop
      case _: AssertionError => Stop
    }

  import beamServices._
  private val config: Beam.Agentsim = beamConfig.beam.agentsim

  var runSender: ActorRef = _
  private val errorListener = context.actorOf(ErrorListener.props())
  context.watch(errorListener)
  context.system.eventStream.subscribe(errorListener, classOf[BeamAgent.TerminatedPrematurelyEvent])
  private val scheduler = context.actorOf(
    Props(
      classOf[BeamAgentScheduler],
      beamConfig,
      Time.parseTime(beamConfig.matsim.modules.qsim.endTime).toInt,
      config.schedulerParallelismWindow,
      new StuckFinder(beamConfig.beam.debug.stuckAgentDetection)
    ),
    "scheduler"
  )
  context.system.eventStream.subscribe(errorListener, classOf[DeadLetter])
  context.watch(scheduler)

  private val parkingManager = context.actorOf(
    ZonalParkingManager
      .props(beamScenario.beamConfig, beamScenario.tazTreeMap, geo, beamRouter),
    "ParkingManager"
  )
  context.watch(parkingManager)

  private val rideHailManager = context.actorOf(
    Props(
      new RideHailManager(
        Id.create("GlobalRHM", classOf[RideHailManager]),
        beamServices,
        beamScenario,
        beamScenario.transportNetwork,
        tollCalculator,
        matsimServices.getScenario,
        matsimServices.getEvents,
        scheduler,
        beamRouter,
        parkingManager,
        rideHailSurgePricingManager,
        rideHailIterationHistory.oscillationAdjustedTNCIterationStats,
        beamSkimmer,
        routeHistory
      )
    ),
    "RideHailManager"
  )
  context.watch(rideHailManager)
  scheduler ! ScheduleTrigger(InitializeTrigger(0), rideHailManager)

  var memoryLoggingTimerActorRef: ActorRef = _
  var memoryLoggingTimerCancellable: Cancellable = _

  var debugActorWithTimerActorRef: ActorRef = _
  var debugActorWithTimerCancellable: Cancellable = _

  if (beamConfig.beam.debug.debugActorTimerIntervalInSec > 0) {
    debugActorWithTimerActorRef = context.actorOf(Props(classOf[DebugActorWithTimer], rideHailManager, scheduler))
    debugActorWithTimerCancellable = prepareMemoryLoggingTimerActor(
      beamConfig.beam.debug.debugActorTimerIntervalInSec,
      context.system,
      debugActorWithTimerActorRef
    )
  }

  private val sharedVehicleFleets = config.agents.vehicles.sharedFleets.map { fleetConfig =>
    context.actorOf(
      Fleets.lookup(fleetConfig).props(beamServices, beamSkimmer, scheduler, parkingManager),
      fleetConfig.name
    )
  }
  sharedVehicleFleets.foreach(context.watch)
  sharedVehicleFleets.foreach(scheduler ! ScheduleTrigger(InitializeTrigger(0), _))

  val transitSystem = context.actorOf(
    Props(
      new TransitSystem(
        beamScenario,
        matsimServices.getScenario,
        beamScenario.transportNetwork,
        scheduler,
        parkingManager,
        tollCalculator,
        geo,
        networkHelper,
        matsimServices.getEvents
      )
    ),
    "transit-system"
  )
  context.watch(transitSystem)
  scheduler ! ScheduleTrigger(InitializeTrigger(0), transitSystem)

  private val population = context.actorOf(
    Population.props(
      matsimServices.getScenario,
      beamScenario,
      beamServices,
      scheduler,
      beamScenario.transportNetwork,
      tollCalculator,
      beamRouter,
      rideHailManager,
      parkingManager,
      sharedVehicleFleets,
      matsimServices.getEvents,
      routeHistory,
      beamSkimmer,
      travelTimeObserved
    ),
    "population"
  )
  context.watch(population)
  scheduler ! ScheduleTrigger(InitializeTrigger(0), population)

  scheduleRideHailManagerTimerMessages()

  def prepareMemoryLoggingTimerActor(
    timeoutInSeconds: Int,
    system: ActorSystem,
    memoryLoggingTimerActorRef: ActorRef
  ): Cancellable = {
    import system.dispatcher

    val cancellable = system.scheduler.schedule(
      0.milliseconds,
      (timeoutInSeconds * 1000).milliseconds,
      memoryLoggingTimerActorRef,
      Tick
    )

    cancellable
  }

  override def receive: PartialFunction[Any, Unit] = {

    case CompletionNotice(_, _) =>
      log.info("Scheduler is finished.")
      endSegment("agentsim-execution", "agentsim")
      log.info("Ending Agentsim")
      log.info("Processing Agentsim Events (Start)")
      startSegment("agentsim-events", "agentsim")

      population ! Finish
      rideHailManager ! Finish
      context.stop(scheduler)
      context.stop(errorListener)
      context.stop(parkingManager)
      context.stop(transitSystem)
      sharedVehicleFleets.foreach(context.stop)
      if (beamConfig.beam.debug.debugActorTimerIntervalInSec > 0) {
        debugActorWithTimerCancellable.cancel()
        context.stop(debugActorWithTimerActorRef)
      }
    case Terminated(_) =>
      if (context.children.isEmpty) {
        context.stop(self)
        runSender ! Success("Ran.")
      } else {
        log.debug("Remaining: {}", context.children)
      }

    case "Run!" =>
      runSender = sender
      log.info("Running BEAM Mobsim")
      endSegment("iteration-preparation", "mobsim")

      log.info("Preparing new Iteration (End)")
      log.info("Starting Agentsim")
      startSegment("agentsim-execution", "agentsim")

      scheduler ! StartSchedule(matsimServices.getIterationNumber)
  }

  private def scheduleRideHailManagerTimerMessages(): Unit = {
    if (config.agents.rideHail.allocationManager.repositionTimeoutInSeconds > 0)
      scheduler ! ScheduleTrigger(RideHailRepositioningTrigger(0), rideHailManager)
    if (config.agents.rideHail.allocationManager.requestBufferTimeoutInSeconds > 0)
      scheduler ! ScheduleTrigger(BufferedRideHailRequestsTrigger(0), rideHailManager)
  }

}
