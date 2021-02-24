package beam.sim

import akka.actor.Status.Success
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Cancellable, DeadLetter, Props, Terminated}
import akka.pattern.ask
import akka.util.Timeout
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.ridehail.RideHailManager.{BufferedRideHailRequestsTrigger, RideHailRepositioningTrigger}
import beam.agentsim.agents.ridehail.{
  RideHailDepotParkingManager,
  RideHailIterationHistory,
  RideHailManager,
  RideHailSurgePricingManager
}
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, VehicleCategory, VehicleManager, VehicleManagerType}
import beam.agentsim.agents.{BeamAgent, InitializeTrigger, Population, TransitSystem}
import beam.agentsim.events.eventbuilder.EventBuilderActor.{EventBuilderActorCompleted, FlushEvents}
import beam.agentsim.infrastructure.{
  ChargingNetworkInfo,
  ChargingNetworkManager,
  ParkingNetworkInfo,
  ParkingNetworkManager
}
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger, StartSchedule}
import beam.replanning.{AddSupplementaryTrips, ModeIterationPlanCleaner, SupplementaryTripGenerator}
import beam.router.Modes.BeamMode
import beam.router._
import beam.router.osm.TollCalculator
import beam.router.skim.TAZSkimsCollector
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig.Beam
import beam.sim.metrics.SimulationMetricCollector.SimulationTime
import beam.sim.metrics.{Metrics, MetricsSupport, SimulationMetricCollector}
import beam.sim.monitoring.ErrorListener
import beam.sim.population.AttributesOfIndividual
import beam.sim.vehiclesharing.Fleets
import beam.utils._
import beam.utils.matsim_conversion.ShapeUtils.QuadTreeBounds
import com.conveyal.r5.transit.TransportNetwork
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.population.{Activity, Leg, Person, Population => MATSimPopulation}
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.mobsim.framework.Mobsim
import org.matsim.core.utils.misc.Time
import org.matsim.households.Households

import java.util.concurrent.TimeUnit
import scala.collection.JavaConverters._
import scala.collection.mutable
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
  val planCleaner: ModeIterationPlanCleaner,
  val networkHelper: NetworkHelper,
  val rideHailFleetInitializerProvider: RideHailFleetInitializerProvider,
) extends Mobsim
    with LazyLogging
    with MetricsSupport {
  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)

  import beamServices._
  val physsimConfig = beamConfig.beam.physsim

  override def run(): Unit = {
    logger.info("Starting Iteration")
    startMeasuringIteration(matsimServices.getIterationNumber)
    logger.info("Preparing new Iteration (Start)")
    startMeasuring("iteration-preparation:mobsim")

    validateVehicleTypes()

    if (beamConfig.beam.debug.debugEnabled)
      logger.info(DebugLib.getMemoryLogMessage("run.start (after GC): "))
    Metrics.iterationNumber = matsimServices.getIterationNumber
    // This is needed to get all iterations in Grafana. Take a look to variable `$iteration_num` in the dashboard
    simMetricCollector.writeIteration(
      "beam-iteration",
      SimulationTime(0),
      matsimServices.getIterationNumber.toLong
    )

    // to have zero values for graphs even if there are no values calculated during iteration
    def writeZeros(
      metricName: String,
      values: Map[String, Double] = Map(SimulationMetricCollector.defaultMetricName -> 0.0),
      tags: Map[String, String] = Map()
    ): Unit = {
      for (hour <- 0 to 24) {
        simMetricCollector.write(metricName, SimulationTime(60 * 60 * hour), values, tags)
      }
    }

    Seq(
      "car",
      "walk",
      "ride_hail",
      "ride_hail_pooled",
      "ride_hail_transit",
      "bike",
      "walk_transit",
      "drive_transit"
    ).foreach(mode => {
      writeZeros("mode-choices", tags = Map("mode" -> mode))
    })

    val defaultName = SimulationMetricCollector.defaultMetricName
    writeZeros("ride-hail-trip-distance", tags = Map("trip-type" -> "1"))
    writeZeros("average-travel-time", tags = Map("mode"          -> "car"))

    writeZeros("parking", tags = Map("parking-type" -> "Public"))
    writeZeros("ride-hail-allocation-reserved")
    writeZeros("ride-hail-inquiry-served")
    writeZeros(
      "chargingPower",
      Map(defaultName   -> 0.0, "averageLoad"        -> 0.0),
      Map("vehicleType" -> "Personal", "parkingType" -> "Public", "typeOfCharger" -> "None")
    )

    eventsManager.initProcessing()

    clearRoutesAndModesIfNeeded(matsimServices.getIterationNumber)
    planCleaner.clearModesAccordingToStrategy(matsimServices.getIterationNumber)

    if (beamConfig.beam.agentsim.agents.tripBehaviors.mulitnomialLogit.generate_secondary_activities) {
      logger.info("Filling in secondary trips in plans")
      fillInSecondaryActivities(matsimServices.getScenario.getHouseholds)
    }

    val iteration = actorSystem.actorOf(
      Props(
        new BeamMobsimIteration(
          beamServices,
          eventsManager,
          rideHailSurgePricingManager,
          rideHailIterationHistory,
          routeHistory,
          rideHailFleetInitializerProvider
        )
      ),
      "BeamMobsim.iteration"
    )
    Await.result(iteration ? "Run!", timeout.duration)

    logger.info("Agentsim finished.")
    eventsManager.finishProcessing()
    logger.info("Events drained.")
    stopMeasuring("agentsim-events:agentsim")

    logger.info("Processing Agentsim Events (End)")
  }

  private def fillInSecondaryActivities(households: Households): Unit = {
    households.getHouseholds.values.forEach { household =>
      val vehicles = household.getVehicleIds.asScala
        .flatten(vehicleId => beamScenario.privateVehicles.get(vehicleId.asInstanceOf[Id[BeamVehicle]]))
      val persons = household.getMemberIds.asScala.collect {
        case personId => matsimServices.getScenario.getPopulation.getPersons.get(personId)
      }
      val destinationChoiceModel = beamScenario.destinationChoiceModel

      val vehiclesByCategory =
        vehicles.filter(_.beamVehicleType.automationLevel <= 3).groupBy(_.beamVehicleType.vehicleCategory)

      val nonCavModesAvailable: List[BeamMode] = vehiclesByCategory.keys.collect {
        case VehicleCategory.Car  => BeamMode.CAR
        case VehicleCategory.Bike => BeamMode.BIKE
      }.toList

      val cavs = vehicles.filter(_.beamVehicleType.automationLevel > 3).toList

      val cavModeAvailable: List[BeamMode] =
        if (cavs.nonEmpty) {
          List[BeamMode](BeamMode.CAV)
        } else {
          List[BeamMode]()
        }

      val modesAvailable: List[BeamMode] = nonCavModesAvailable ++ cavModeAvailable

      persons.foreach { person =>
        if (matsimServices.getIterationNumber == 0) {
          val addSupplementaryTrips = new AddSupplementaryTrips(beamScenario.beamConfig)
          addSupplementaryTrips.run(person)
        }

        if (person.getSelectedPlan.getPlanElements.asScala
              .collect { case activity: Activity => activity.getType }
              .contains("Temp")) {
          val supplementaryTripGenerator =
            new SupplementaryTripGenerator(
              person.getCustomAttributes.get("beam-attributes").asInstanceOf[AttributesOfIndividual],
              destinationChoiceModel,
              beamServices,
              person.getId
            )
          val newPlan =
            supplementaryTripGenerator.generateNewPlans(person.getSelectedPlan, destinationChoiceModel, modesAvailable)
          newPlan match {
            case Some(plan) =>
              person.removePlan(person.getSelectedPlan)
              person.addPlan(plan)
              person.setSelectedPlan(plan)
            case None =>
          }
        }
      }

    }

    logger.info("Done filling in secondary trips in plans")
  }

  private def clearRoutesAndModesIfNeeded(iteration: Int): Unit = {
    val experimentType = physsimConfig.relaxation.`type`
    if (experimentType == "experiment_2.0") {
      if (physsimConfig.relaxation.experiment2_0.clearRoutesEveryIteration) {
        clearRoutes()
        logger.info(s"Experiment_2.0: Clear all routes at iteration ${iteration}")
      }
      if (physsimConfig.relaxation.experiment2_0.clearModesEveryIteration) {
        clearModes()
        logger.info(s"Experiment_2.0: Clear all modes at iteration ${iteration}")
      }
    } else if (experimentType == "experiment_2.1") {
      if (physsimConfig.relaxation.experiment2_1.clearRoutesEveryIteration) {
        clearRoutes()
        logger.info(s"Experiment_2.1: Clear all routes at iteration ${iteration}")
      }
      if (physsimConfig.relaxation.experiment2_1.clearModesEveryIteration) {
        clearModes()
        logger.info(s"Experiment_2.1: Clear all modes at iteration ${iteration}")
      }
    } else if (experimentType == "experiment_3.0" && iteration <= 1) {
      clearRoutes()
      logger.info(s"Experiment_3.0: Clear all routes at iteration ${iteration}")
      clearModes()
      logger.info(s"Experiment_3.0: Clear all modes at iteration ${iteration}")
    } else if (experimentType == "experiment_4.0" && iteration <= 1) {
      clearRoutes()
      logger.info(s"Experiment_4.0: Clear all routes at iteration ${iteration}")
      clearModes()
      logger.info(s"Experiment_4.0: Clear all modes at iteration ${iteration}")
    } else if (experimentType == "experiment_5.0" && iteration <= 1) {
      clearRoutes()
      logger.info(s"Experiment_5.0: Clear all routes at iteration ${iteration}")
      clearModes()
      logger.info(s"Experiment_5.0: Clear all modes at iteration ${iteration}")
    } else if (experimentType == "experiment_5.1" && iteration <= 1) {
      clearRoutes()
      logger.info(s"Experiment_5.1: Clear all routes at iteration ${iteration}")
      clearModes()
      logger.info(s"Experiment_5.1: Clear all modes at iteration ${iteration}")
    } else if (experimentType == "experiment_5.2" && iteration <= 1) {
      clearRoutes()
      logger.info(s"Experiment_5.2: Clear all routes at iteration ${iteration}")
      clearModes()
      logger.info(s"Experiment_5.2: Clear all modes at iteration ${iteration}")
    }
  }

  private def clearRoutes(): Unit = {
    scenario.getPopulation.getPersons.values().asScala.foreach { p =>
      p.getPlans.asScala.foreach { plan =>
        plan.getPlanElements.asScala.foreach {
          case leg: Leg =>
            leg.setRoute(null)
          case _ =>
        }
      }
    }
  }

  private def clearModes(): Unit = {
    scenario.getPopulation.getPersons.values().asScala.foreach { p =>
      p.getPlans.asScala.foreach { plan =>
        plan.getPlanElements.asScala.foreach {
          case leg: Leg =>
            leg.setMode("")
          case _ =>
        }
      }
    }
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
  val eventsManager: EventsManager,
  val rideHailSurgePricingManager: RideHailSurgePricingManager,
  val rideHailIterationHistory: RideHailIterationHistory,
  val routeHistory: RouteHistory,
  val rideHailFleetInitializerProvider: RideHailFleetInitializerProvider,
) extends Actor
    with ActorLogging
    with MetricsSupport {

  import beamServices._
  private val config: Beam.Agentsim = beamConfig.beam.agentsim

  var runSender: ActorRef = _
  private val errorListener = context.actorOf(ErrorListener.props())
  context.watch(errorListener)
  context.system.eventStream.subscribe(errorListener, classOf[BeamAgent.TerminatedPrematurelyEvent])
  context.system.eventStream.subscribe(errorListener, classOf[DeadLetter])
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
  context.watch(scheduler)

  private val envelopeInUTM = geo.wgs2Utm(beamScenario.transportNetwork.streetLayer.envelope)
  envelopeInUTM.expandBy(beamConfig.beam.spatial.boundingBoxBuffer)

  val activityQuadTreeBounds: QuadTreeBounds = buildActivityQuadTreeBounds(matsimServices.getScenario.getPopulation)
  log.info(s"envelopeInUTM before expansion: $envelopeInUTM")

  envelopeInUTM.expandToInclude(activityQuadTreeBounds.minx, activityQuadTreeBounds.miny)
  envelopeInUTM.expandToInclude(activityQuadTreeBounds.maxx, activityQuadTreeBounds.maxy)
  log.info(s"envelopeInUTM after expansion: $envelopeInUTM")

  // Vehicle Managers
  val vehicleManagers: Map[Id[VehicleManager], VehicleManager] = prepareVehicleManagers()

  // Parking Network Manager
  private val parkingNetworkInfo = ParkingNetworkInfo(beamServices, envelopeInUTM, vehicleManagers)
  private val parkingNetworkManager = context.actorOf(
    ParkingNetworkManager
      .props(beamServices, parkingNetworkInfo)
      .withDispatcher("parking-network-manager-pinned-dispatcher"),
    "ParkingNetworkManager"
  )
  context.watch(parkingNetworkManager)

  // Charging Network Manager
  private val chargingNetworkInfo = ChargingNetworkInfo(beamServices, envelopeInUTM, vehicleManagers)
  private val chargingNetworkManager = context.actorOf(
    ChargingNetworkManager
      .props(beamServices, chargingNetworkInfo, parkingNetworkManager, scheduler)
      .withDispatcher("charging-network-manager-pinned-dispatcher"),
    "ChargingNetworkManager"
  )
  context.watch(chargingNetworkManager)
  scheduler ! ScheduleTrigger(InitializeTrigger(0), chargingNetworkManager)

  val rideHailManagerId: Id[VehicleManager] =
    vehicleManagers.filter(_._2.managerType == VehicleManagerType.Ridehail).head._1
  private val rideHailFleetInitializer = rideHailFleetInitializerProvider.get()
  private val rideHailManager = context.actorOf(
    Props(
      new RideHailManager(
        rideHailManagerId,
        beamServices,
        beamScenario,
        beamScenario.transportNetwork,
        tollCalculator,
        matsimServices.getScenario,
        matsimServices.getEvents,
        scheduler,
        beamRouter,
        parkingNetworkManager,
        chargingNetworkManager,
        envelopeInUTM,
        activityQuadTreeBounds,
        rideHailSurgePricingManager,
        rideHailIterationHistory.oscillationAdjustedTNCIterationStats,
        routeHistory,
        rideHailFleetInitializer,
        parkingNetworkInfo.getRideHailParking.asInstanceOf[RideHailDepotParkingManager[_]]
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
      Fleets.lookup(fleetConfig).props(beamServices, scheduler, parkingNetworkManager),
      fleetConfig.name
    )
  }
  sharedVehicleFleets.foreach(context.watch)
  sharedVehicleFleets.foreach(scheduler ! ScheduleTrigger(InitializeTrigger(0), _))

  private val transitSystem = context.actorOf(
    Props(
      new TransitSystem(
        beamServices,
        beamScenario,
        matsimServices.getScenario,
        beamScenario.transportNetwork,
        scheduler,
        parkingNetworkManager,
        chargingNetworkManager,
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
      parkingNetworkManager,
      chargingNetworkManager,
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

  //to monitor with TAZSkimmer add actor hereinafter
  private val tazSkimmer = context.actorOf(
    TAZSkimsCollector.props(scheduler, beamServices, rideHailManager +: sharedVehicleFleets),
    "taz-skims-collector"
  )
  context.watch(tazSkimmer)
  scheduler ! ScheduleTrigger(InitializeTrigger(0), tazSkimmer)

  def prepareMemoryLoggingTimerActor(
    timeoutInSeconds: Int,
    system: ActorSystem,
    memoryLoggingTimerActorRef: ActorRef
  ): Cancellable = {
    import system.dispatcher

    val cancellable = system.scheduler.scheduleWithFixedDelay(
      0.milliseconds,
      (timeoutInSeconds * 1000).milliseconds,
      memoryLoggingTimerActorRef,
      Tick
    )

    cancellable
  }

  def prepareVehicleManagers(): Map[Id[VehicleManager], VehicleManager] = {
    val managers = mutable.HashMap.empty[Id[VehicleManager], VehicleManager]
    managers.put(VehicleManager.privateVehicleManager.managerId, VehicleManager.privateVehicleManager)
    managers.put(VehicleManager.transitVehicleManager.managerId, VehicleManager.transitVehicleManager)
    managers.put(VehicleManager.bodiesVehicleManager.managerId, VehicleManager.bodiesVehicleManager)
    val rideHailVehicleManager: VehicleManager =
      VehicleManager.create(
        Id.create(beamServices.beamConfig.beam.agentsim.agents.rideHail.vehicleManagerId, classOf[VehicleManager]),
        Some(VehicleCategory.Car),
        isRideHail = true
      )
    managers.put(rideHailVehicleManager.managerId, rideHailVehicleManager)
    config.agents.vehicles.sharedFleets.map { fleetConfig =>
      val sharedId = Id.create(fleetConfig.name, classOf[VehicleManager])
      managers.put(
        sharedId,
        VehicleManager.create(
          sharedId,
          Some(VehicleCategory.Car),
          isShared = true
        )
      )
    }
    managers.toMap
  }

  override def receive: PartialFunction[Any, Unit] = {

    case CompletionNotice(_, _) =>
      log.info("Scheduler is finished.")
      stopMeasuring("agentsim-execution:agentsim")
      log.info("Ending Agentsim")
      log.info("Processing Agentsim Events (Start)")
      stopMeasuring("agentsim-events:agentsim")

      population ! Finish
      rideHailManager ! Finish
      transitSystem ! Finish
      tazSkimmer ! Finish
      chargingNetworkManager ! Finish
      context.stop(scheduler)
      context.stop(errorListener)
      context.stop(parkingNetworkManager)
      sharedVehicleFleets.foreach(context.stop)
      if (beamConfig.beam.debug.debugActorTimerIntervalInSec > 0) {
        debugActorWithTimerCancellable.cancel()
        context.stop(debugActorWithTimerActorRef)
      }

    case Terminated(_) =>
      if (context.children.isEmpty) {
        // Await eventBuilder message queue to be processed, before ending iteration
        beamServices.eventBuilderActor ! FlushEvents
      } else {
        log.debug("Remaining: {}", context.children)
      }

    case EventBuilderActorCompleted =>
      runSender ! Success("Ran.")
      context.stop(self)

    case "Run!" =>
      runSender = sender
      log.info("Running BEAM Mobsim")
      stopMeasuring("iteration-preparation:mobsim")

      log.info("Preparing new Iteration (End)")
      log.info("Starting Agentsim")
      startMeasuring("agentsim-execution:agentsim")

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
