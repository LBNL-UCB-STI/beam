package beam.sim

import java.awt.Color
import java.lang.Double
import java.util.Random
import java.util.concurrent.TimeUnit
import java.util.stream.Stream

import akka.actor.Status.Success
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Cancellable, DeadLetter, Identify, Props, Terminated}
import akka.pattern.ask
import akka.util.Timeout
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.BeamVehicleStateUpdate
import beam.agentsim.agents.ridehail.RideHailManager.{
  BufferedRideHailRequestsTimeout,
  NotifyIterationEnds,
  RideHailAllocationManagerTimeout
}
import beam.agentsim.agents.ridehail.{RideHailAgent, RideHailManager, RideHailSurgePricingManager}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles._
import beam.agentsim.infrastructure.ParkingManager.ParkingStockAttributes
import beam.agentsim.infrastructure.{ParkingManager, TAZTreeMap, ZonalParkingManager}
import beam.agentsim.scheduler.{BeamAgentScheduler, Trigger}
import beam.agentsim.agents.{BeamAgent, InitializeTrigger, Population}
import beam.agentsim.infrastructure.ParkingManager.ParkingStockAttributes
import beam.agentsim.infrastructure.ZonalParkingManager
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger, StartSchedule}
import beam.router.BeamRouter.InitTransit
import beam.sim.metrics.MetricsSupport
import beam.sim.monitoring.ErrorListener
import beam.utils._
import beam.utils.matsim_conversion.ShapeUtils.QuadTreeBounds
import com.conveyal.r5.transit.TransportNetwork
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.population.{Activity, Person}
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.mobsim.framework.Mobsim
import org.matsim.core.utils.misc.Time
import org.matsim.households.Household
import org.matsim.vehicles.{Vehicle, VehicleType, VehicleUtils}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
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
  val scenario: Scenario,
  val eventsManager: EventsManager,
  val actorSystem: ActorSystem,
  val rideHailSurgePricingManager: RideHailSurgePricingManager
) extends Mobsim
    with LazyLogging
    with MetricsSupport {
  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)

  var memoryLoggingTimerActorRef: ActorRef = _
  var memoryLoggingTimerCancellable: Cancellable = _

  var rideHailAgents: ArrayBuffer[ActorRef] = new ArrayBuffer()

  val rideHailHouseholds: mutable.Set[Id[Household]] =
    mutable.Set[Id[Household]]()

  var debugActorWithTimerActorRef: ActorRef = _
  var debugActorWithTimerCancellable: Cancellable = _
  /*
    var rideHailSurgePricingManager: RideHailSurgePricingManager = injector.getInstance(classOf[BeamServices])
    new RideHailSurgePricingManager(beamServices.beamConfig,beamServices.taz);*/

  def getQuadTreeBound[p <: Person](persons: Stream[p]): QuadTreeBounds = {

    var minX: Double = null
    var maxX: Double = null
    var minY: Double = null
    var maxY: Double = null

    persons.forEach { person =>
      val planElementsIterator =
        person.getSelectedPlan.getPlanElements.iterator()
      while (planElementsIterator.hasNext) {
        val planElement = planElementsIterator.next()
        planElement match {
          case activity: Activity =>
            val coord = activity.getCoord
            minX = if (minX == null || minX > coord.getX) coord.getX else minX
            maxX = if (maxX == null || maxX < coord.getX) coord.getX else maxX
            minY = if (minY == null || minY > coord.getY) coord.getY else minY
            maxY = if (maxY == null || maxY < coord.getY) coord.getY else maxY
          case _ =>
        }
      }
    }

    QuadTreeBounds(minX, minY, maxX, maxY)
  }

  override def run(): Unit = {
    logger.info("Starting Iteration")
    startMeasuringIteration(beamServices.iterationNumber)
    logger.info("Preparing new Iteration (Start)")
    startSegment("iteration-preparation", "mobsim")

    if (beamServices.beamConfig.beam.debug.debugEnabled)
      logger.info(DebugLib.gcAndGetMemoryLogMessage("run.start (after GC): "))
    beamServices.startNewIteration
    eventsManager.initProcessing()
    val iteration = actorSystem.actorOf(
      Props(new Actor with ActorLogging {
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
          ).withDispatcher("beam-agent-scheduler-pinned-dispatcher"),
          "scheduler"
        )
        context.system.eventStream.subscribe(errorListener, classOf[DeadLetter])
        context.watch(scheduler)

        beamServices.vehicles.clear() // important to purge data from previous iteration

        private val envelopeInUTM =
          beamServices.geo.wgs2Utm(transportNetwork.streetLayer.envelope)
        envelopeInUTM.expandBy(beamServices.beamConfig.beam.spatial.boundingBoxBuffer)

        private val parkingManager = context.actorOf(
          ZonalParkingManager
            .props(beamServices, beamServices.beamRouter, ParkingStockAttributes(100)).withDispatcher("zonal-parking-manager-pinned-dispatcher"),
          "ParkingManager"
        )
        context.watch(parkingManager)

        private val rideHailManager = context.actorOf(
          RideHailManager.props(
            beamServices,
            scheduler,
            beamServices.beamRouter,
            parkingManager,
            envelopeInUTM,
            rideHailSurgePricingManager
          ).withDispatcher("ride-hail-manager-pinned-dispatcher"),
          "RideHailManager"
        )
        context.watch(rideHailManager)

        beamServices.vehicleTypes.get(
          Id.create(beamServices.beamConfig.beam.agentsim.agents.rideHail.vehicleTypeId, classOf[BeamVehicleType])
        ) match {
          case Some(rhVehType) =>
            if (beamServices.beamConfig.beam.agentsim.agents.rideHail.refuelThresholdInMeters >= rhVehType.primaryFuelCapacityInJoule / rhVehType.primaryFuelConsumptionInJoule * 0.8) {
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
          debugActorWithTimerActorRef = context.actorOf(Props(classOf[DebugActorWithTimer], rideHailManager, scheduler))
          debugActorWithTimerCancellable = prepareMemoryLoggingTimerActor(
            beamServices.beamConfig.beam.debug.debugActorTimerIntervalInSec,
            context.system,
            debugActorWithTimerActorRef
          )
        }

        private val population = context.actorOf(
          Population.props(
            scenario,
            beamServices,
            scheduler,
            transportNetwork,
            beamServices.beamRouter,
            rideHailManager,
            parkingManager,
            eventsManager
          ),
          "population"
        )
        context.watch(population)
        Await.result(population ? Identify(0), timeout.duration)

        private val numRideHailAgents = math.round(
          beamServices.beamConfig.beam.agentsim.numAgents.toDouble * beamServices.beamConfig.beam.agentsim.agents.rideHail.numDriversAsFractionOfPopulation
        )
        private val rideHailVehicleType = BeamVehicleUtils
          .getVehicleTypeById(
            beamServices.beamConfig.beam.agentsim.agents.rideHail.vehicleTypeId,
            scenario.getVehicles.getVehicleTypes
          )
          .getOrElse(scenario.getVehicles.getVehicleTypes.get(Id.create("1", classOf[VehicleType])))

        val quadTreeBounds: QuadTreeBounds = getQuadTreeBound(
          scenario.getPopulation.getPersons
            .values()
            .stream()
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
            .forEach(x => {
              val personInitialLocation: Coord =
                x.getSelectedPlan.getPlanElements
                  .iterator()
                  .next()
                  .asInstanceOf[Activity]
                  .getCoord
              activityLocationsSpatialPlot
                .addPoint(PointToPlot(personInitialLocation, Color.BLUE, 10))
            })

          activityLocationsSpatialPlot.writeImage(
            beamServices.matsimServices.getControlerIO
              .getIterationFilename(beamServices.iterationNumber, "activityLocations.png")
          )
        }

        val persons: Iterable[Person] = RandomUtils.shuffle(scenario.getPopulation.getPersons.values().asScala, rand)
        persons.view.take(numRideHailAgents.toInt).foreach {
          person =>
            val personInitialLocation: Coord =
              person.getSelectedPlan.getPlanElements
                .iterator()
                .next()
                .asInstanceOf[Activity]
                .getCoord
            val rideInitialLocation: Coord =
              beamServices.beamConfig.beam.agentsim.agents.rideHail.initialLocation.name match {
                case RideHailManager.INITIAL_RIDEHAIL_LOCATION_HOME =>
                  val radius =
                    beamServices.beamConfig.beam.agentsim.agents.rideHail.initialLocation.home.radiusInMeters
                  new Coord(
                    personInitialLocation.getX + radius * (rand.nextDouble() - 0.5),
                    personInitialLocation.getY + radius * (rand.nextDouble() - 0.5)
                  )
                case RideHailManager.INITIAL_RIDEHAIL_LOCATION_UNIFORM_RANDOM =>
                  val x = quadTreeBounds.minx + (quadTreeBounds.maxx - quadTreeBounds.minx) * rand
                    .nextDouble()
                  val y = quadTreeBounds.miny + (quadTreeBounds.maxy - quadTreeBounds.miny) * rand
                    .nextDouble()
                  new Coord(x, y)
                case RideHailManager.INITIAL_RIDEHAIL_LOCATION_ALL_AT_CENTER =>
                  val x = quadTreeBounds.minx + (quadTreeBounds.maxx - quadTreeBounds.minx) / 2
                  val y = quadTreeBounds.miny + (quadTreeBounds.maxy - quadTreeBounds.miny) / 2
                  new Coord(x, y)
                case RideHailManager.INITIAL_RIDEHAIL_LOCATION_ALL_IN_CORNER =>
                  val x = quadTreeBounds.minx
                  val y = quadTreeBounds.miny
                  new Coord(x, y)
                case unknown =>
                  log.error(s"unknown rideHail.initialLocation $unknown")
                  null
              }

            val rideHailName = s"rideHailAgent-${person.getId}"

            val rideHailVehicleId = BeamVehicle.createId(person.getId, Some("rideHailVehicle"))
            //                Id.createVehicleId(s"rideHailVehicle-${person.getId}")

            val ridehailBeamVehicleTypeId =
              Id.create(beamServices.beamConfig.beam.agentsim.agents.rideHail.vehicleTypeId, classOf[BeamVehicleType])
            val ridehailBeamVehicleType = beamServices.vehicleTypes
              .get(ridehailBeamVehicleTypeId)
              .getOrElse(BeamVehicleType.defaultCarBeamVehicleType)

            val rideHailAgentPersonId: Id[RideHailAgent] =
              Id.create(rideHailName, classOf[RideHailAgent])

            val powertrain = Option(ridehailBeamVehicleType.primaryFuelConsumptionInJoule)
              .map(new Powertrain(_))
              .getOrElse(Powertrain.PowertrainFromMilesPerGallon(Powertrain.AverageMilesPerGallon))

            val rideHailBeamVehicle = new BeamVehicle(
              rideHailVehicleId,
              powertrain,
              None,
              ridehailBeamVehicleType
            )
            beamServices.vehicles += (rideHailVehicleId -> rideHailBeamVehicle)
            rideHailBeamVehicle.registerResource(rideHailManager)

            rideHailManager ! BeamVehicleStateUpdate(
              rideHailBeamVehicle.getId,
              rideHailBeamVehicle.getState()
            )

            val rideHailAgentProps = RideHailAgent.props(
              beamServices,
              scheduler,
              transportNetwork,
              eventsManager,
              parkingManager,
              rideHailAgentPersonId,
              rideHailBeamVehicle,
              rideInitialLocation
            )
            val rideHailAgentRef: ActorRef =
              context.actorOf(rideHailAgentProps, rideHailName)
            context.watch(rideHailAgentRef)
            scheduler ! ScheduleTrigger(InitializeTrigger(0), rideHailAgentRef)
            rideHailAgents += rideHailAgentRef

            rideHailinitialLocationSpatialPlot
              .addString(StringToPlot(s"${person.getId}", rideInitialLocation, Color.RED, 20))
            rideHailinitialLocationSpatialPlot
              .addAgentWithCoord(
                RideHailAgentInitCoord(rideHailAgentPersonId, rideInitialLocation)
              )
        }

        if (beamServices.matsimServices != null) {
          rideHailinitialLocationSpatialPlot.writeCSV(
            beamServices.matsimServices.getControlerIO
              .getIterationFilename(beamServices.iterationNumber, "rideHailInitialLocation.csv")
          )
          rideHailinitialLocationSpatialPlot.writeImage(
            beamServices.matsimServices.getControlerIO
              .getIterationFilename(beamServices.iterationNumber, "rideHailInitialLocation.png")
          )
        }
        log.info(s"Initialized ${beamServices.personRefs.size} people")
        log.info(s"Initialized ${scenario.getVehicles.getVehicles.size()} personal vehicles")
        log.info(s"Initialized $numRideHailAgents ride hailing agents")

        Await.result(beamServices.beamRouter ? InitTransit(scheduler, parkingManager), timeout.duration)

        if (beamServices.iterationNumber == 0)
          new BeamWarmStart(beamServices).init()

        log.info(s"Transit schedule has been initialized")

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
            cleanupRideHailingAgents()
            endSegment("agentsim-execution", "agentsim")
            log.info("Ending Agentsim")
            log.info("Processing Agentsim Events (Start)")
            startSegment("agentsim-events", "agentsim")

            cleanupRideHailingAgents()
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
          val timerTrigger = RideHailAllocationManagerTimeout(0)
          val timerMessage = ScheduleTrigger(timerTrigger, rideHailManager)
          scheduler ! timerMessage

          scheduler ! ScheduleTrigger(BufferedRideHailRequestsTimeout(0), rideHailManager)
          log.info(s"rideHailManagerTimerScheduled")
        }

        private def cleanupRideHailingAgents(): Unit = {
          rideHailAgents.foreach(_ ! Finish)
          rideHailAgents = new ArrayBuffer()

        }

      }),
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
