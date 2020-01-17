package beam.sim

import java.util.concurrent.TimeUnit

import akka.actor.Status.Success
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Cancellable, DeadLetter, Props, Terminated}
import akka.pattern.ask
import akka.util.Timeout
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.ridehail.RideHailManager.{BufferedRideHailRequestsTrigger, RideHailRepositioningTrigger}
import beam.agentsim.agents.ridehail.{RideHailIterationHistory, RideHailManager, RideHailSurgePricingManager}
import beam.agentsim.agents.vehicles.BeamVehicleType
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
import beam.utils.matsim_conversion.ShapeUtils.QuadTreeBounds
import com.conveyal.r5.transit.TransportNetwork
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.population.{Activity, Person, Population => MATSimPopulation}
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.mobsim.framework.Mobsim
import org.matsim.core.utils.misc.Time

import scala.collection.JavaConverters._
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

    validateVehicleTypes()

    if (beamServices.beamConfig.beam.debug.debugEnabled)
      logger.info(DebugLib.getMemoryLogMessage("run.start (after GC): "))
    Metrics.iterationNumber = beamServices.matsimServices.getIterationNumber
    eventsManager.initProcessing()

    val iteration = actorSystem.actorOf(
      Props(
        new BeamMobsimIteration(
          beamServices,
          rideHailSurgePricingManager,
          rideHailIterationHistory,
          routeHistory
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

  def validateVehicleTypes(): Unit = {
    if (!beamScenario.vehicleTypes.contains(
          Id.create(beamScenario.beamConfig.beam.agentsim.agents.bodyType, classOf[BeamVehicleType])
        )) {
      throw new RuntimeException(
        "Vehicle type for human body: " + beamScenario.beamConfig.beam.agentsim.agents.bodyType + " is missing. Please add it to the vehicle types."
      )
    }
    if (!beamScenario.vehicleTypes.contains(
          Id.create(
            beamScenario.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId,
            classOf[BeamVehicleType]
          )
        )) {
      throw new RuntimeException(
        "Vehicle type for ride-hail: " + beamScenario.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId + " is missing. Please add it to the vehicle types."
      )
    }
  }

}

class BeamMobsimIteration(
  val beamServices: BeamServices,
  val rideHailSurgePricingManager: RideHailSurgePricingManager,
  val rideHailIterationHistory: RideHailIterationHistory,
  val routeHistory: RouteHistory
) extends Actor
    with ActorLogging
    with MetricsSupport {
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
    ).withDispatcher("beam-agent-scheduler-pinned-dispatcher"),
    "scheduler"
  )
  context.system.eventStream.subscribe(errorListener, classOf[DeadLetter])
  context.watch(scheduler)

  private val envelopeInUTM = geo.wgs2Utm(beamScenario.transportNetwork.streetLayer.envelope)
  envelopeInUTM.expandBy(beamConfig.beam.spatial.boundingBoxBuffer)

  val activityQuadTreeBounds: QuadTreeBounds = buildActivityQuadTreeBounds(matsimServices.getScenario.getPopulation)
  log.info(s"envelopeInUTM before expansion: $envelopeInUTM")

  envelopeInUTM.expandToInclude(activityQuadTreeBounds.minx, activityQuadTreeBounds.miny)
  envelopeInUTM.expandToInclude(activityQuadTreeBounds.maxx, activityQuadTreeBounds.maxy)
  log.info(s"envelopeInUTM after expansion: $envelopeInUTM")

  private val parkingManager = context.actorOf(
    ZonalParkingManager
      .props(beamScenario.beamConfig, beamScenario.tazTreeMap, geo, beamRouter, envelopeInUTM)
      .withDispatcher("zonal-parking-manager-pinned-dispatcher"),
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
        envelopeInUTM,
        activityQuadTreeBounds,
        rideHailSurgePricingManager,
        rideHailIterationHistory.oscillationAdjustedTNCIterationStats,
        routeHistory
      )
    ).withDispatcher("ride-hail-manager-pinned-dispatcher"),
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
      Fleets.lookup(fleetConfig).props(beamServices, scheduler, parkingManager),
      fleetConfig.name
    )
  }
  sharedVehicleFleets.foreach(context.watch)
  sharedVehicleFleets.foreach(scheduler ! ScheduleTrigger(InitializeTrigger(0), _))

  private val transitSystem = context.actorOf(
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
      envelopeInUTM
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
      transitSystem ! Finish
      context.stop(scheduler)
      context.stop(errorListener)
      context.stop(parkingManager)
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
    if (config.agents.rideHail.repositioningManager.timeout > 0) {
      // We need to stagger init tick for repositioning manager and allocation manager
      // This is important because during the `requestBufferTimeoutInSeconds` repositioned vehicle is not available, so to make them work together
      // we have to make sure that there is no overlap
      val initTick = config.agents.rideHail.repositioningManager.timeout / 2
      scheduler ! ScheduleTrigger(RideHailRepositioningTrigger(initTick), rideHailManager)
    }
    if (config.agents.rideHail.allocationManager.requestBufferTimeoutInSeconds > 0)
      scheduler ! ScheduleTrigger(BufferedRideHailRequestsTrigger(0), rideHailManager)
  }

  def buildActivityQuadTreeBounds(population: MATSimPopulation): QuadTreeBounds = {
    val persons = population.getPersons.values().asInstanceOf[java.util.Collection[Person]].asScala.view
    val activities = persons.flatMap(p => p.getSelectedPlan.getPlanElements.asScala.view).collect {
      case activity: Activity =>
        activity
    }
    val coordinates = activities.map(_.getCoord)
    // Force to compute xs and ys arrays
    val xs = coordinates.map(_.getX).toArray
    val ys = coordinates.map(_.getY).toArray
    val xMin = xs.min
    val xMax = xs.max
    val yMin = ys.min
    val yMax = ys.max
    log.info(
      s"QuadTreeBounds with X: [$xMin; $xMax], Y: [$yMin, $yMax]. boundingBoxBuffer: ${beamConfig.beam.spatial.boundingBoxBuffer}"
    )
    QuadTreeBounds(
      xMin - beamConfig.beam.spatial.boundingBoxBuffer,
      yMin - beamConfig.beam.spatial.boundingBoxBuffer,
      xMax + beamConfig.beam.spatial.boundingBoxBuffer,
      yMax + beamConfig.beam.spatial.boundingBoxBuffer
    )
  }

}
