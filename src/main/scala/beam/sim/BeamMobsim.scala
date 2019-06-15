package beam.sim

import java.util.concurrent.TimeUnit

import akka.actor.Status.Success
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Cancellable, DeadLetter, Identify, Props, Terminated}
import akka.pattern.ask
import akka.util.Timeout
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.ridehail.RideHailManager.{BufferedRideHailRequestsTrigger, RideHailRepositioningTrigger}
import beam.agentsim.agents.ridehail.{RideHailIterationHistory, RideHailManager, RideHailSurgePricingManager}
import beam.agentsim.agents.{BeamAgent, InitializeTrigger, Population}
import beam.agentsim.infrastructure.ZonalParkingManager
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger, StartSchedule}
import beam.router.BeamRouter.InitTransit
import beam.router.osm.TollCalculator
import beam.router.{BeamRouter, BeamSkimmer, FreeFlowTravelTime, RouteHistory}
import beam.sim.config.BeamConfig.Beam
import beam.sim.metrics.MetricsSupport
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

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.collection.JavaConverters._

/**
  * AgentSim.
  *
  * Created by sfeygin on 2/8/17.
  */
class BeamMobsim @Inject()(
  val beamServices: BeamServices,
  val transportNetwork: TransportNetwork,
  val tollCalculator: TollCalculator,
  val scenario: Scenario,
  val eventsManager: EventsManager,
  val actorSystem: ActorSystem,
  val rideHailSurgePricingManager: RideHailSurgePricingManager,
  val rideHailIterationHistory: RideHailIterationHistory,
  val routeHistory: RouteHistory,
  val beamSkimmer: BeamSkimmer
) extends Mobsim
    with LazyLogging
    with MetricsSupport {
  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)

  val RideHailManagerInitTimeout: FiniteDuration = 100.seconds

  var memoryLoggingTimerActorRef: ActorRef = _
  var memoryLoggingTimerCancellable: Cancellable = _

  var debugActorWithTimerActorRef: ActorRef = _
  var debugActorWithTimerCancellable: Cancellable = _
  private val config: Beam.Agentsim = beamServices.beamConfig.beam.agentsim

  override def run(): Unit = {
    logger.info("Starting Iteration")
    startMeasuringIteration(beamServices.iterationNumber)
    logger.info("Preparing new Iteration (Start)")
    startSegment("iteration-preparation", "mobsim")

    if (beamServices.beamConfig.beam.debug.debugEnabled)
      logger.info(DebugLib.gcAndGetMemoryLogMessage("run.start (after GC): "))
    beamServices.startNewIteration()
    eventsManager.initProcessing()
    val iteration = actorSystem.actorOf(
      Props(new Actor with ActorLogging {
        var runSender: ActorRef = _
        private val errorListener = context.actorOf(ErrorListener.props())
        context.watch(errorListener)
        context.system.eventStream.subscribe(errorListener, classOf[BeamAgent.TerminatedPrematurelyEvent])
        private val scheduler = context.actorOf(
          Props(
            classOf[BeamAgentScheduler],
            beamServices.beamConfig,
            Time.parseTime(beamServices.beamConfig.matsim.modules.qsim.endTime).toInt,
            config.schedulerParallelismWindow,
            new StuckFinder(beamServices.beamConfig.beam.debug.stuckAgentDetection)
          ),
          "scheduler"
        )
        context.system.eventStream.subscribe(errorListener, classOf[DeadLetter])
        context.watch(scheduler)

        private val envelopeInUTM =
          beamServices.geo.wgs2Utm(transportNetwork.streetLayer.envelope)
        envelopeInUTM.expandBy(beamServices.beamConfig.beam.spatial.boundingBoxBuffer)

        val activityQuadTreeBounds: QuadTreeBounds = buildActivityQuadTreeBounds(scenario.getPopulation)
        log.info(s"envelopeInUTM before expansion: $envelopeInUTM")

        envelopeInUTM.expandToInclude(activityQuadTreeBounds.minx, activityQuadTreeBounds.miny)
        envelopeInUTM.expandToInclude(activityQuadTreeBounds.maxx, activityQuadTreeBounds.maxy)
        log.info(s"envelopeInUTM after expansion: $envelopeInUTM")

        private val parkingManager = context.actorOf(
          ZonalParkingManager
            .props(beamServices, beamServices.beamRouter, envelopeInUTM),
          "ParkingManager"
        )
        context.watch(parkingManager)

        private val rideHailManager = context.actorOf(
          Props(
            new RideHailManager(
              Id.create("GlobalRHM", classOf[RideHailManager]),
              beamServices,
              transportNetwork,
              tollCalculator,
              scenario,
              eventsManager,
              scheduler,
              beamServices.beamRouter,
              parkingManager,
              envelopeInUTM,
              activityQuadTreeBounds,
              rideHailSurgePricingManager,
              rideHailIterationHistory.oscillationAdjustedTNCIterationStats,
              beamSkimmer,
              routeHistory
            )
          ),
          "RideHailManager"
        )
        context.watch(rideHailManager)
        ProfilingUtils.timed("rideHailManager identified", x => log.info(x)) {
          Await.result(rideHailManager ? Identify(0), RideHailManagerInitTimeout)
        }
        if (beamServices.beamConfig.beam.debug.debugActorTimerIntervalInSec > 0) {
          debugActorWithTimerActorRef = context.actorOf(Props(classOf[DebugActorWithTimer], rideHailManager, scheduler))
          debugActorWithTimerCancellable = prepareMemoryLoggingTimerActor(
            beamServices.beamConfig.beam.debug.debugActorTimerIntervalInSec,
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

        private val population = context.actorOf(
          Population.props(
            scenario,
            beamServices,
            scheduler,
            transportNetwork,
            tollCalculator,
            beamServices.beamRouter,
            rideHailManager,
            parkingManager,
            sharedVehicleFleets,
            eventsManager,
            routeHistory,
            beamSkimmer,
            envelopeInUTM
          ),
          "population"
        )
        context.watch(population)
        Await.result(population ? Identify(0), timeout.duration)
        Await.result(beamServices.beamRouter ? InitTransit(scheduler, parkingManager), timeout.duration)

        log.info("Transit schedule has been initialized")

        if (beamServices.iterationNumber == 0) {
          val maxHour = TimeUnit.SECONDS.toHours(scenario.getConfig.travelTimeCalculator().getMaxTime).toInt
          val warmStart = BeamWarmStart(beamServices.beamConfig, maxHour)
          warmStart.warmStartTravelTime(beamServices.beamRouter, scenario)

          if (!beamServices.beamConfig.beam.warmStart.enabled && beamServices.beamConfig.beam.physsim.initializeRouterWithFreeFlowTimes) {
            FreeFlowTravelTime.initializeRouterFreeFlow(beamServices, scenario)
          }
        }

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
            sharedVehicleFleets.foreach(context.stop)
            if (beamServices.beamConfig.beam.debug.debugActorTimerIntervalInSec > 0) {
              debugActorWithTimerCancellable.cancel()
              context.stop(debugActorWithTimerActorRef)
            }
            if (beamServices.beamConfig.beam.debug.memoryConsumptionDisplayTimeoutInSec > 0) {
              //              memoryLoggingTimerCancellable.cancel()
              //              context.stop(memoryLoggingTimerActorRef)
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

            scheduler ! StartSchedule(beamServices.iterationNumber)
        }

        private def scheduleRideHailManagerTimerMessages(): Unit = {
          if (config.agents.rideHail.allocationManager.repositionTimeoutInSeconds > 0)
            scheduler ! ScheduleTrigger(RideHailRepositioningTrigger(0), rideHailManager)
          if (config.agents.rideHail.allocationManager.requestBufferTimeoutInSeconds > 0)
            scheduler ! ScheduleTrigger(BufferedRideHailRequestsTrigger(0), rideHailManager)
        }

      }),
      "BeamMobsim.iteration"
    )
    Await.result(iteration ? "Run!", timeout.duration)
    beamServices.beamRouter ! BeamRouter.IterationFinished(beamServices.iterationNumber)

    logger.info("Agentsim finished.")
    eventsManager.finishProcessing()
    logger.info("Events drained.")
    endSegment("agentsim-events", "agentsim")

    logger.info("Processing Agentsim Events (End)")
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
    logger.info(
      s"QuadTreeBounds with X: [$xMin; $xMax], Y: [$yMin, $yMax]. boundingBoxBuffer: ${beamServices.beamConfig.beam.spatial.boundingBoxBuffer}"
    )
    QuadTreeBounds(
      xMin - beamServices.beamConfig.beam.spatial.boundingBoxBuffer,
      yMin - beamServices.beamConfig.beam.spatial.boundingBoxBuffer,
      xMax + beamServices.beamConfig.beam.spatial.boundingBoxBuffer,
      yMax + beamServices.beamConfig.beam.spatial.boundingBoxBuffer
    )
  }

}
