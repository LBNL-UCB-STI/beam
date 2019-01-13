package beam.sim

import java.awt.Color
import java.util.Random
import java.util.concurrent.TimeUnit

import akka.actor.Status.Success
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Cancellable, DeadLetter, Identify, Props, Terminated}
import akka.pattern.ask
import akka.util.Timeout
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.ridehail.RideHailManager.{
  BufferedRideHailRequestsTrigger,
  NotifyIterationEnds,
  RideHailRepositioningTrigger
}
import beam.agentsim.agents.ridehail.{RideHailIterationHistory, RideHailManager, RideHailSurgePricingManager}
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.{BeamAgent, Population}
import beam.agentsim.infrastructure.ParkingManager.ParkingStockAttributes
import beam.agentsim.infrastructure.ZonalParkingManager
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger, StartSchedule}
import beam.router.BeamRouter.{InitTransit, UpdateTravelTimeLocal, UpdateTravelTimeRemote}
import beam.router.FreeFlowTravelTime
import beam.router.osm.TollCalculator
import beam.sim.metrics.MetricsSupport
import beam.sim.monitoring.ErrorListener
import beam.sim.vehiclesharing.{FixedNonReservingVehicleFleet, InexhaustibleReservingVehicleFleet}
import beam.utils._
import beam.utils.matsim_conversion.ShapeUtils.QuadTreeBounds
import com.conveyal.r5.transit.TransportNetwork
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.population.{Activity, Person}
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup
import org.matsim.core.mobsim.framework.Mobsim
import org.matsim.core.utils.misc.Time

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration._

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
  val rideHailIterationHistory: RideHailIterationHistory
) extends Mobsim
    with LazyLogging
    with MetricsSupport {
  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)

  var memoryLoggingTimerActorRef: ActorRef = _
  var memoryLoggingTimerCancellable: Cancellable = _

  var debugActorWithTimerActorRef: ActorRef = _
  var debugActorWithTimerCancellable: Cancellable = _

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
      Props(
        new Actor with ActorLogging {
          var runSender: ActorRef = _
          private val errorListener = context.actorOf(ErrorListener.props())
          context.watch(errorListener)
          context.system.eventStream
            .subscribe(errorListener, classOf[BeamAgent.TerminatedPrematurelyEvent])
          private val scheduler = context.actorOf(
            Props(
              classOf[BeamAgentScheduler],
              beamServices.beamConfig,
              Time.parseTime(beamServices.beamConfig.matsim.modules.qsim.endTime).toInt,
              beamServices.beamConfig.beam.agentsim.schedulerParallelismWindow,
              new StuckFinder(beamServices.beamConfig.beam.debug.stuckAgentDetection)
            ),
            "scheduler"
          )
          context.system.eventStream.subscribe(errorListener, classOf[DeadLetter])
          context.watch(scheduler)

          private val envelopeInUTM =
            beamServices.geo.wgs2Utm(transportNetwork.streetLayer.envelope)
          envelopeInUTM.expandBy(beamServices.beamConfig.beam.spatial.boundingBoxBuffer)

          private val parkingManager = context.actorOf(
            ZonalParkingManager
              .props(beamServices, beamServices.beamRouter, ParkingStockAttributes(100)),
            "ParkingManager"
          )
          context.watch(parkingManager)

          private val rideHailManager = context.actorOf(
            Props(
              classOf[RideHailManager],
              beamServices,
              transportNetwork,
              tollCalculator,
              scenario,
              eventsManager,
              scheduler,
              beamServices.beamRouter,
              parkingManager,
              envelopeInUTM,
              rideHailSurgePricingManager,
              rideHailIterationHistory.oscillationAdjustedTNCIterationStats
            ),
            "RideHailManager"
          )
          context.watch(rideHailManager)

          private val vehicleTypeId: Id[BeamVehicleType] = Id
            .create(
              beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId,
              classOf[BeamVehicleType]
            )

          beamServices.vehicleTypes.get(vehicleTypeId) match {
            case Some(rhVehType) =>
              if (beamServices.beamConfig.beam.agentsim.agents.rideHail.refuelThresholdInMeters >= rhVehType.primaryFuelCapacityInJoule / rhVehType.primaryFuelConsumptionInJoulePerMeter * 0.8) {
                log.error(
                  "Ride Hail refuel threshold is higher than state of energy of a vehicle fueled by a DC fast charger. This will cause an infinite loop"
                )
              }
            case None =>
              log.error(
                "Ride Hail vehicle type (param: beamServices.beamConfig.beam.agentsim.agents.rideHail.vehicleTypeId) could not be found"
              )
          }

          if (beamServices.beamConfig.beam.debug.debugActorTimerIntervalInSec > 0) {
            debugActorWithTimerActorRef = context
              .actorOf(Props(classOf[DebugActorWithTimer], rideHailManager, scheduler))
            debugActorWithTimerCancellable = prepareMemoryLoggingTimerActor(
              beamServices.beamConfig.beam.debug.debugActorTimerIntervalInSec,
              context.system,
              debugActorWithTimerActorRef
            )
          }
          def initialSharedVehicleLocations =
            scenario.getPopulation.getPersons
              .values()
              .asScala
              .map(Population.personInitialLocation)

          private val sharedVehicleFleets =
            Vector(
              context
                .actorOf(
                  Props(new InexhaustibleReservingVehicleFleet(parkingManager)),
                  "inexhaustible-shared-vehicle-fleet"
                ),
              context
                .actorOf(
                  Props(new FixedNonReservingVehicleFleet(parkingManager, initialSharedVehicleLocations)),
                  "fixed-non-reserving-vehicle-fleet"
                )
            )
          sharedVehicleFleets.foreach(context.watch)

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
              eventsManager
            ),
            "population"
          )
          context.watch(population)
          Await.result(population ? Identify(0), timeout.duration)

          private val numRideHailAgents = math.round(
            beamServices.beamConfig.beam.agentsim.numAgents.toDouble * beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.numDriversAsFractionOfPopulation
          )

          val rand: Random =
            new Random(beamServices.beamConfig.matsim.modules.global.randomSeed)

          val rideHailinitialLocationSpatialPlot = new SpatialPlot(1100, 1100, 50)
          val activityLocationsSpatialPlot = new SpatialPlot(1100, 1100, 50)

          if (beamServices.matsimServices != null) {

            scenario.getPopulation.getPersons
              .values()
              .forEach(
                x =>
                  x.getSelectedPlan.getPlanElements.forEach {
                    case z: Activity =>
                      activityLocationsSpatialPlot.addPoint(PointToPlot(z.getCoord, Color.RED, 10))
                    case _ =>
                }
              )

            scenario.getPopulation.getPersons
              .values()
              .forEach(
                x => {
                  val personInitialLocation: Coord =
                    x.getSelectedPlan.getPlanElements
                      .iterator()
                      .next()
                      .asInstanceOf[Activity]
                      .getCoord
                  activityLocationsSpatialPlot
                    .addPoint(PointToPlot(personInitialLocation, Color.BLUE, 10))
                }
              )

            if (beamServices.beamConfig.beam.outputs.writeGraphs) {
              activityLocationsSpatialPlot.writeImage(
                beamServices.matsimServices.getControlerIO
                  .getIterationFilename(beamServices.iterationNumber, "activityLocations.png")
              )
            }
          }

          log.info("Initialized {} people", beamServices.personRefs.size)
          log.info("Initialized {} personal vehicles", beamServices.privateVehicles.size)
          log.info("Initialized {} ride hailing agents", numRideHailAgents)

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
              val future = rideHailManager.ask(NotifyIterationEnds())
              Await.ready(future, timeout.duration).value
              context.stop(rideHailManager)
              context.stop(scheduler)
              context.stop(errorListener)
              context.stop(parkingManager)
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
            if (beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.repositionTimeoutInSeconds > 0)
              scheduler ! ScheduleTrigger(RideHailRepositioningTrigger(0), rideHailManager)
            if (beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.requestBufferTimeoutInSeconds > 0)
              scheduler ! ScheduleTrigger(BufferedRideHailRequestsTrigger(0), rideHailManager)
          }

        }
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
