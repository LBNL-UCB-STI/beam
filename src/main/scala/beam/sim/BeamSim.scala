package beam.sim

import java.io.{BufferedWriter, File, FileWriter}
import java.nio.file.{Files, Path, Paths}
import java.util.Collections
import java.util.concurrent.TimeUnit
import akka.actor.{ActorSystem, Identify}
import akka.pattern.ask
import akka.util.Timeout
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.agentsim.agents.ridehail.allocation.RideHailResourceAllocationManager
import beam.agentsim.agents.ridehail.{RideHailIterationHistory, RideHailIterationsStatsCollector}
import beam.agentsim.events.eventbuilder.EventBuilderActor
import beam.agentsim.events.handling.TravelTimeGoogleStatistic
import beam.analysis._
import beam.analysis.cartraveltime.{
  CarTripStatsFromPathTraversalEventHandler,
  StudyAreaTripFilter,
  TakeAllTripsTripFilter
}
import beam.analysis.plots.modality.ModalityStyleStats
import beam.analysis.plots.{GraphUtils, GraphsStatsAgentSimEventsListener}
import beam.analysis.via.ExpectedMaxUtilityHeatMap
import beam.physsim.jdeqsim.AgentSimToPhysSimPlanConverter
import beam.router.BeamRouter.ODSkimmerReady
import beam.router.BeamRouter.UpdateTravelTimeLocal
import beam.router.Modes.BeamMode
import beam.router.osm.TollCalculator
import beam.router.r5.RouteDumper
import beam.router.skim.urbansim.{BackgroundSkimsCreator, GeoClustering, H3Clustering, TAZClustering}
import beam.router.{BeamRouter, FreeFlowTravelTime, RouteHistory}
import beam.sim.config.{BeamConfig, BeamConfigHolder}
import beam.sim.metrics.{BeamStaticMetricsWriter, MetricsSupport}
import beam.utils.watcher.MethodWatcher
import org.matsim.core.router.util.TravelTime

import scala.util.Try
import scala.concurrent.duration._

import scala.util.control.NonFatal
import beam.utils.csv.writers._
import beam.utils.logging.ExponentialLazyLogging
import beam.utils.scripts.FailFast
import beam.utils.{DebugLib, NetworkHelper, ProfilingUtils, SummaryVehicleStatsParser}
import com.conveyal.r5.transit.TransportNetwork
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.utils.objectattributes.ObjectAttributesXmlWriter
import beam.analysis.PythonProcess
import org.apache.commons.lang3.StringUtils
import org.jfree.data.category.DefaultCategoryDataset
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.population.{Activity, Plan}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.corelisteners.DumpDataAtEnd
import org.matsim.core.controler.events._
import org.matsim.core.controler.listener.{
  IterationEndsListener,
  IterationStartsListener,
  ShutdownListener,
  StartupListener
}

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.collection.{immutable, mutable}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

class BeamSim @Inject()(
  private val actorSystem: ActorSystem,
  private val transportNetwork: TransportNetwork,
  private val tollCalculator: TollCalculator,
  private val beamServices: BeamServices,
  private val eventsManager: EventsManager,
  private val scenario: Scenario,
  private val beamScenario: BeamScenario,
  private val networkHelper: NetworkHelper,
  private val beamOutputDataDescriptionGenerator: BeamOutputDataDescriptionGenerator,
  private val beamConfigChangesObservable: BeamConfigChangesObservable,
  private val routeHistory: RouteHistory,
  private val rideHailIterationHistory: RideHailIterationHistory,
  private val configHolder: BeamConfigHolder
) extends StartupListener
    with IterationStartsListener
    with IterationEndsListener
    with ShutdownListener
    with LazyLogging
    with MetricsSupport {

  val COLLECT_AND_CREATE_BEAM_ANALYSIS_AND_GRAPHS =
    beamServices.beamConfig.beam.outputs.collectAndCreateBeamAnalysisAndGraphs
  private var agentSimToPhysSimPlanConverter: AgentSimToPhysSimPlanConverter = _
  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)

  private var createGraphsFromEvents: GraphsStatsAgentSimEventsListener = _
  private var delayMetricAnalysis: DelayMetricAnalysis = _
  private var modalityStyleStats: ModalityStyleStats = _
  private var expectedDisutilityHeatMapDataCollector: ExpectedMaxUtilityHeatMap = _

  private val modeChoiceAlternativesCollector: ModeChoiceAlternativesCollector = new ModeChoiceAlternativesCollector(
    beamServices
  )

  private val maybeRealizedModeChoiceWriter: Option[RealizedModeChoiceWriter] =
    Option(beamServices.beamConfig.beam.debug.writeRealizedModeChoiceFile).collect {
      case true => new RealizedModeChoiceWriter(beamServices)
    }

  private var tncIterationsStatsCollector: Option[RideHailIterationsStatsCollector] = None
  val iterationStatsProviders: ListBuffer[IterationStatsProvider] = new ListBuffer()
  val iterationSummaryStats: ListBuffer[Map[java.lang.String, java.lang.Double]] = ListBuffer()
  val graphFileNameDirectory: mutable.Map[String, Int] = mutable.Map[String, Int]()
  // var metricsPrinter: ActorRef = actorSystem.actorOf(MetricsPrinter.props())
  val summaryData = new mutable.HashMap[String, mutable.Map[Int, Double]]()
  val runningPythonScripts: ListBuffer[PythonProcess] = mutable.ListBuffer.empty[PythonProcess]

  val rideHailUtilizationCollector: RideHailUtilizationCollector = new RideHailUtilizationCollector(beamServices)

  val routeDumper: RouteDumper = new RouteDumper(beamServices)

  val transitOccupancyByStop = new TransitOccupancyByStopAnalysis()

  val startAndEndEventListeners: List[BasicEventHandler with IterationStartsListener with IterationEndsListener] =
    List(routeDumper)

  val carTravelTimeFromPtes: List[CarTripStatsFromPathTraversalEventHandler] = {
    val normalCarTravelTime = new CarTripStatsFromPathTraversalEventHandler(
      networkHelper,
      beamServices.matsimServices.getControlerIO,
      TakeAllTripsTripFilter,
      "",
      treatMismatchAsWarning = true
    )
    val studyAreCarTravelTime = if (beamServices.beamConfig.beam.calibration.studyArea.enabled) {
      Some(
        new CarTripStatsFromPathTraversalEventHandler(
          networkHelper,
          beamServices.matsimServices.getControlerIO,
          new StudyAreaTripFilter(beamServices.beamConfig.beam.calibration.studyArea, beamServices.geo),
          "studyarea",
          treatMismatchAsWarning = false // It is expected that for study area some PathTraversals will be taken, so do not treat it as warning
        )
      )
    } else {
      None
    }
    List(Some(normalCarTravelTime), studyAreCarTravelTime).flatten
  }

  val travelTimeGoogleStatistic: TravelTimeGoogleStatistic =
    new TravelTimeGoogleStatistic(
      beamServices.beamConfig.beam.calibration.google.travelTimes,
      actorSystem,
      beamServices.geo
    )

  val vmInformationWriter: VMInformationCollector = new VMInformationCollector(
    beamServices.matsimServices.getControlerIO
  )

  var maybeConsecutivePopulationLoader: Option[ConsecutivePopulationLoader] = None

  beamServices.modeChoiceCalculatorFactory = ModeChoiceCalculator(
    beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass,
    beamServices,
    configHolder,
    eventsManager
  )

  var backgroundSkimsCreator: Option[BackgroundSkimsCreator] = None

  private var initialTravelTime = Option.empty[TravelTime]

  override def notifyStartup(event: StartupEvent): Unit = {
    maybeConsecutivePopulationLoader =
      if (beamServices.beamConfig.beam.physsim.relaxation.`type` == "consecutive_increase_of_population") {
        val consecutivePopulationLoader = new ConsecutivePopulationLoader(
          scenario,
          Array(10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0),
          new java.util.Random(beamServices.beamConfig.matsim.modules.global.randomSeed)
        )
        consecutivePopulationLoader.cleanScenario()
        consecutivePopulationLoader.load()
        Some(consecutivePopulationLoader)
      } else None

    //      metricsPrinter ! Subscribe("counter", "**")
    //      metricsPrinter ! Subscribe("histogram", "**")

    if (COLLECT_AND_CREATE_BEAM_ANALYSIS_AND_GRAPHS) {
      eventsManager.addHandler(transitOccupancyByStop)
      eventsManager.addHandler(modeChoiceAlternativesCollector)
      eventsManager.addHandler(rideHailUtilizationCollector)
      carTravelTimeFromPtes.foreach(eventsManager.addHandler)
    }

    eventsManager.addHandler(travelTimeGoogleStatistic)
    startAndEndEventListeners.foreach(eventsManager.addHandler)
    maybeRealizedModeChoiceWriter.foreach(eventsManager.addHandler(_))

    beamServices.beamRouter = actorSystem.actorOf(
      BeamRouter.props(
        beamServices.beamScenario,
        transportNetwork,
        scenario.getNetwork,
        networkHelper,
        beamServices.geo,
        scenario,
        scenario.getTransitVehicles,
        beamServices.fareCalculator,
        tollCalculator,
        eventsManager
      ),
      "router"
    )
    initialTravelTime = BeamWarmStart.warmStartTravelTime(
      beamServices.beamConfig,
      beamServices.beamRouter,
      scenario
    )
    Await.result(beamServices.beamRouter ? Identify(0), timeout.duration)

    /*    if(null != beamServices.beamConfig.beam.agentsim.taz.file && !beamServices.beamConfig.beam.agentsim.taz.file.isEmpty)
          beamServices.taz = TAZTreeMap.fromCsv(beamServices.beamConfig.beam.agentsim.taz.file)*/

    if (!beamServices.beamConfig.beam.physsim.skipPhysSim) {
      agentSimToPhysSimPlanConverter = new AgentSimToPhysSimPlanConverter(
        eventsManager,
        transportNetwork,
        event.getServices.getControlerIO,
        scenario,
        beamServices,
        beamConfigChangesObservable
      )
      iterationStatsProviders += agentSimToPhysSimPlanConverter
    }

    if (COLLECT_AND_CREATE_BEAM_ANALYSIS_AND_GRAPHS) {
      createGraphsFromEvents = new GraphsStatsAgentSimEventsListener(
        eventsManager,
        event.getServices.getControlerIO,
        beamServices,
        beamServices.beamConfig
      )
      iterationStatsProviders += createGraphsFromEvents
    }
    modalityStyleStats = new ModalityStyleStats()
    expectedDisutilityHeatMapDataCollector = new ExpectedMaxUtilityHeatMap(
      eventsManager,
      beamServices,
      event.getServices.getControlerIO
    )

    if (COLLECT_AND_CREATE_BEAM_ANALYSIS_AND_GRAPHS) {

      if (RideHailResourceAllocationManager.requiredRideHailIterationsStatsCollector(
            beamServices.beamConfig.beam.agentsim.agents.rideHail
          )) {
        tncIterationsStatsCollector = Some(
          new RideHailIterationsStatsCollector(
            eventsManager,
            beamServices,
            rideHailIterationHistory,
            transportNetwork
          )
        )
      }

      delayMetricAnalysis = new DelayMetricAnalysis(
        eventsManager,
        event.getServices.getControlerIO,
        networkHelper
      )
    }

    val controllerIO = event.getServices.getControlerIO

    // FIXME: Remove this once ready to merge
    // generateRandomCoordinates()

    PopulationCsvWriter.toCsv(scenario, controllerIO.getOutputFilename("population.csv.gz"))
    VehiclesCsvWriter(beamServices).toCsv(scenario, controllerIO.getOutputFilename("vehicles.csv.gz"))
    HouseholdsCsvWriter.toCsv(scenario, controllerIO.getOutputFilename("households.csv.gz"))
    NetworkCsvWriter.toCsv(scenario, controllerIO.getOutputFilename("network.csv.gz"))

    dumpMatsimStuffAtTheBeginningOfSimulation()

    // These metric are used to display all other metrics in Grafana.
    // For example take a look to `run_name` variable in the dashboard
    BeamStaticMetricsWriter.writeBaseMetrics(beamScenario, beamServices)

    if (beamServices.beamConfig.beam.urbansim.backgroundODSkimsCreator.enabled) {
      val geoClustering: GeoClustering =
        beamServices.beamConfig.beam.urbansim.backgroundODSkimsCreator.skimsGeoType match {
          case "h3" =>
            new H3Clustering(
              beamServices.matsimServices.getScenario.getPopulation,
              beamServices.geo,
              beamServices.beamConfig.beam.urbansim.backgroundODSkimsCreator.numberOfH3Indexes
            )
          case "taz" => new TAZClustering(beamScenario.tazTreeMap)
        }

      val abstractSkimmer = BackgroundSkimsCreator.createSkimmer(beamServices, geoClustering)
      val backgroundODSkimsCreatorConfig = beamServices.beamConfig.beam.urbansim.backgroundODSkimsCreator
      val skimCreator = new BackgroundSkimsCreator(
        beamServices,
        beamScenario,
        geoClustering,
        abstractSkimmer,
        new FreeFlowTravelTime,
        Array(BeamMode.WALK),
        withTransit = backgroundODSkimsCreatorConfig.modesToBuild.walk_transit,
        buildDirectWalkRoute = backgroundODSkimsCreatorConfig.modesToBuild.walk,
        buildDirectCarRoute = false,
        calculationTimeoutHours = backgroundODSkimsCreatorConfig.calculationTimeoutHours
      )(actorSystem)
      skimCreator.start()
      backgroundSkimsCreator = Some(skimCreator)
    }

    FailFast.run(beamServices)
    if (beamServices.beamConfig.beam.routing.overrideNetworkTravelTimesUsingSkims) {
      beamServices.beamRouter ! ODSkimmerReady(beamServices.skims.od_skimmer)
    }
  }

  override def notifyIterationStarts(event: IterationStartsEvent): Unit = {
    backgroundSkimsCreator.foreach(_.reduceParallelismTo(1))
    beamServices.eventBuilderActor = actorSystem.actorOf(
      EventBuilderActor.props(
        beamServices.beamCustomizationAPI.getEventBuilders(beamServices.matsimServices.getEvents)
      )
    )

    if (event.getIteration > 0) {
      maybeConsecutivePopulationLoader.foreach { cpl =>
        cpl.load()
        agentSimToPhysSimPlanConverter.buildPersonToHousehold()
      }
    }

    beamServices.beamRouter ! BeamRouter.IterationStartsMessage(event.getIteration)

    beamConfigChangesObservable.notifyChangeToSubscribers()

    if (beamServices.beamConfig.beam.debug.writeModeChoiceAlternatives) {
      modeChoiceAlternativesCollector.notifyIterationStarts(event)
    }

    maybeRealizedModeChoiceWriter.foreach(_.notifyIterationStarts(event))

    beamServices.modeChoiceCalculatorFactory = ModeChoiceCalculator(
      beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass,
      beamServices,
      configHolder,
      eventsManager
    )

    ExponentialLazyLogging.reset()
    beamServices.beamScenario.privateVehicles.values.foreach(
      _.initializeFuelLevelsFromUniformDistribution(
        beamServices.beamConfig.beam.agentsim.agents.vehicles.meanPrivateVehicleStartingSOC
      )
    )

    val iterationNumber = event.getIteration

    val controllerIO = event.getServices.getControlerIO
    if (isFirstIteration(iterationNumber)) {
      PlansCsvWriter.toCsv(scenario, controllerIO.getOutputFilename("plans.csv.gz"))
    }

    if (COLLECT_AND_CREATE_BEAM_ANALYSIS_AND_GRAPHS) {
      rideHailUtilizationCollector.reset(event.getIteration)
    }

    travelTimeGoogleStatistic.reset(event.getIteration)
    startAndEndEventListeners.foreach(_.notifyIterationStarts(event))

    beamServices.simMetricCollector.clear()

  }

  private def shouldWritePlansAtCurrentIteration(iterationNumber: Int): Boolean = {
    val beamConfig: BeamConfig = beamConfigChangesObservable.getUpdatedBeamConfig
    val interval = beamConfig.beam.outputs.writePlansInterval
    interval > 0 && iterationNumber % interval == 0
  }

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    backgroundSkimsCreator.foreach(_.increaseParallelismTo(Runtime.getRuntime.availableProcessors() - 1))

    val beamConfig: BeamConfig = beamConfigChangesObservable.getUpdatedBeamConfig

    if (shouldWritePlansAtCurrentIteration(event.getIteration)) {
      PlansCsvWriter.toCsv(
        scenario,
        beamServices.matsimServices.getControlerIO.getIterationFilename(event.getIteration, "plans.csv.gz")
      )
    }

    if (beamConfig.beam.debug.debugEnabled)
      logger.info(DebugLib.getMemoryLogMessage("notifyIterationEnds.start (after GC): "))

    if (COLLECT_AND_CREATE_BEAM_ANALYSIS_AND_GRAPHS) {
      rideHailUtilizationCollector.notifyIterationEnds(event)
      carTravelTimeFromPtes.foreach(_.notifyIterationEnds(event))
      transitOccupancyByStop.notifyIterationEnds(event)
    }

    travelTimeGoogleStatistic.notifyIterationEnds(event)
    startAndEndEventListeners.foreach(_.notifyIterationEnds(event))

    if (beamServices.beamConfig.beam.debug.writeModeChoiceAlternatives) {
      modeChoiceAlternativesCollector.notifyIterationEnds(event)
    }

    maybeRealizedModeChoiceWriter.foreach(_.notifyIterationEnds(event))

    val outputGraphsFuture = Future {
      if (COLLECT_AND_CREATE_BEAM_ANALYSIS_AND_GRAPHS) {
        if ("ModeChoiceLCCM".equals(beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass)) {
          modalityStyleStats.processData(scenario.getPopulation, event)
          modalityStyleStats.buildModalityStyleGraph(event.getServices.getControlerIO)
        }
        createGraphsFromEvents.createGraphs(event)

        iterationSummaryStats += iterationStatsProviders
          .flatMap(_.getSummaryStats.asScala)
          .toMap

        val summaryVehicleStatsFile =
          Paths.get(event.getServices.getControlerIO.getOutputFilename("summaryVehicleStats.csv")).toFile
        val unProcessedStats = MethodWatcher.withLoggingInvocationTime(
          "Saving summary vehicle stats",
          writeSummaryVehicleStats(summaryVehicleStatsFile),
          logger.underlying
        )

        val summaryStatsFile = Paths.get(event.getServices.getControlerIO.getOutputFilename("summaryStats.csv")).toFile
        MethodWatcher.withLoggingInvocationTime(
          "Saving summary stats",
          writeSummaryStats(summaryStatsFile, unProcessedStats),
          logger.underlying
        )

        iterationSummaryStats.flatMap(_.keySet).distinct.foreach { x =>
          val key = x.split("_")(0)
          val value = graphFileNameDirectory.getOrElse(key, 0) + 1
          graphFileNameDirectory += key -> value
        }

        val fileNames = iterationSummaryStats.flatMap(_.keySet).distinct.sorted
        MethodWatcher.withLoggingInvocationTime(
          "Creating summary stats graphs",
          fileNames.foreach(file => createSummaryStatsGraph(file, event.getIteration)),
          logger.underlying
        )

        graphFileNameDirectory.clear()

        tncIterationsStatsCollector.foreach(_.tellHistoryToRideHailIterationHistoryActorAndReset())

        if (beamConfig.beam.replanning.Module_2.equalsIgnoreCase("ClearRoutes")) {
          routeHistory.expireRoutes(beamConfig.beam.replanning.ModuleProbability_2)
        }
      }
    }

    if (beamConfig.beam.physsim.skipPhysSim) {
      Await.result(Future.sequence(List(outputGraphsFuture)), Duration.Inf)
    } else {
      val physsimFuture = Future {
        agentSimToPhysSimPlanConverter.startPhysSim(event, initialTravelTime.orNull)
      }

      // executing code blocks parallel
      Await.result(Future.sequence(List(outputGraphsFuture, physsimFuture)), Duration.Inf)
    }

    if (beamConfig.beam.debug.debugEnabled)
      logger.info(DebugLib.getMemoryLogMessage("notifyIterationEnds.end (after GC): "))
    stopMeasuringIteration()

    val persons = scenario.getPopulation.getPersons.values().asScala
    logger.info(
      "Iteration {} - average number of plans per agent: {}",
      event.getIteration,
      persons.map(_.getPlans.size()).sum.toFloat / persons.size
    )

    val activityEndTimesNonNegativeCheck: Iterable[Plan] = persons.toList.flatMap(_.getPlans.asScala.toList) filter {
      plan =>
        val activities = plan.getPlanElements.asScala.filter(_.isInstanceOf[Activity])
        activities.dropRight(1).exists(_.asInstanceOf[Activity].getEndTime < 0)
    }

    if (activityEndTimesNonNegativeCheck.isEmpty) {
      logger.info("All person activities (except the last one) have non-negative end times.")
    } else {
      logger.warn(s"Non-negative end times found for person activities - ${activityEndTimesNonNegativeCheck.size}")
    }

    if (beamConfig.beam.outputs.writeGraphs) {
      // generateRepositioningGraphs(event)
    }

    logger.info("Ending Iteration")
    if (COLLECT_AND_CREATE_BEAM_ANALYSIS_AND_GRAPHS) {
      delayMetricAnalysis.generateDelayAnalysis(event)

      writeEventsAnalysisUsing(event)
    }
    if (beamConfig.beam.debug.vmInformation.createGCClassHistogram) {
      vmInformationWriter.notifyIterationEnds(event)
    }

    // Clear the state of private vehicles because they are shared across iterations
    beamServices.beamScenario.privateVehicles.values.foreach(_.resetState())
  }

  private def writeEventsAnalysisUsing(event: IterationEndsEvent) = {
    if (beamServices.beamConfig.beam.outputs.writeAnalysis) {
      val writeEventsInterval = beamServices.beamConfig.beam.outputs.writeEventsInterval
      val writeEventAnalysisInThisIteration = writeEventsInterval > 0 && event.getIteration % writeEventsInterval == 0
      if (writeEventAnalysisInThisIteration) {
        val currentEventsFilePath =
          event.getServices.getControlerIO.getIterationFilename(event.getServices.getIterationNumber, "events.csv")
        val pythonProcess = beam.analysis.AnalysisProcessor.firePythonScriptAsync(
          "src/main/python/events_analysis/analyze_events.py",
          if (new File(currentEventsFilePath).exists) currentEventsFilePath else currentEventsFilePath + ".gz"
        )
        runningPythonScripts += pythonProcess
      }
    }
  }

  private def dumpMatsimStuffAtTheBeginningOfSimulation(): Unit = {
    ProfilingUtils.timed(
      "dumpMatsimStuffAtTheBeginningOfSimulation in the beginning of simulation",
      x => logger.info(x)
    ) {
      // `DumpDataAtEnd` during `notifyShutdown` dumps network, plans, person attributes and other things.
      // Reusing it to get `outputPersonAttributes.xml.gz` which is needed for warmstart
      val dumper = beamServices.injector.getInstance(classOf[DumpDataAtEnd])
      dumper match {
        case listener: ShutdownListener =>
          val event = new ShutdownEvent(beamServices.matsimServices, false)
          // Create files
          listener.notifyShutdown(event)
          dumpHouseholdAttributes

        case x =>
          logger.warn("dumper is not `ShutdownListener`")
      }
    }
  }

  private def dumpHouseholdAttributes(): Unit = {
    val householdAttributes = scenario.getHouseholds.getHouseholdAttributes
    if (householdAttributes != null) {
      val writer = new ObjectAttributesXmlWriter(householdAttributes)
      writer.setPrettyPrint(true)
      writer.putAttributeConverters(Collections.emptyMap())
      writer.writeFile(
        beamServices.matsimServices.getControlerIO.getOutputFilename("output_householdAttributes.xml.gz")
      )
    }
  }

  private def isFirstIteration(currentIteration: Int): Boolean = {
    val firstIteration = beamServices.beamConfig.matsim.modules.controler.firstIteration
    currentIteration == firstIteration
  }

  override def notifyShutdown(event: ShutdownEvent): Unit = {
    finalizeBackgroundSkimsCreator()

    carTravelTimeFromPtes.foreach(_.notifyShutdown(event))

    val firstIteration = beamServices.beamConfig.matsim.modules.controler.firstIteration
    val lastIteration = beamServices.beamConfig.matsim.modules.controler.lastIteration

    if (COLLECT_AND_CREATE_BEAM_ANALYSIS_AND_GRAPHS) {
      GraphReadmeGenerator.generateGraphReadme(event.getServices.getControlerIO.getOutputPath)

      logger.info("Generating html page to compare graphs (across all iterations)")
      BeamGraphComparator.generateGraphComparisonHtmlPage(event, firstIteration, lastIteration)
      beamOutputDataDescriptionGenerator.generateDescriptors(event)
    }

    if (beamServices.beamConfig.beam.warmStart.prepareData) {
      Try {
        BeamWarmStart.prepareWarmStartArchive(
          beamServices.beamConfig,
          event.getServices.getControlerIO,
          firstIteration,
          lastIteration
        ) foreach { warmStartArchive =>
          logger.info(s"Warmstart archive: $warmStartArchive")
        }
      }.failed.foreach(throwable => logger.error("Cannot create warmstart archive", throwable))
    }
    Await.result(actorSystem.terminate(), Duration.Inf)
    logger.info("Actor system shut down")

    deleteMATSimOutputFiles(event.getServices.getIterationNumber)

    BeamConfigChangesObservable.clear()

    runningPythonScripts
      .filter(process => process.isRunning)
      .foreach(process => {
        logger.info("Waiting for python process to complete running.")
        process.waitFor(5, TimeUnit.MINUTES)
        logger.info("Python process completed.")
      })

    beamServices.simMetricCollector.close()

  }

  def deleteMATSimOutputFiles(lastIterationNumber: Int): Unit = {
    val rootFiles = for {
      fileName <- beamServices.beamConfig.beam.outputs.matsim.deleteRootFolderFiles.split(",")
    } yield Paths.get(beamServices.matsimServices.getControlerIO.getOutputFilename(fileName))

    val iterationFiles = for {
      fileName        <- beamServices.beamConfig.beam.outputs.matsim.deleteITERSFolderFiles.split(",")
      iterationNumber <- 0 to lastIterationNumber
    } yield Paths.get(beamServices.matsimServices.getControlerIO.getIterationFilename(iterationNumber, fileName))

    tryDelete("root files: ", rootFiles)
    tryDelete("iteration files: ", iterationFiles)
  }

  def tryDelete(kindOfFiles: String, filesToDelete: Seq[Path]): Unit = {
    val attempts = deleteFiles(filesToDelete)

    val success = attempts.collect { case Right(filePath) => filePath.getFileName }
    if (success.nonEmpty) {
      val successMsg = success.mkString(s"Succeeded to delete MATSim $kindOfFiles", ", ", "")
      logger.info(successMsg)
    }

    val errors = attempts.collect { case Left(error) => error.getFileName }
    if (errors.nonEmpty) {
      val failureMsg = errors.mkString(s"Failed to delete MATSim $kindOfFiles", ", ", "")
      logger.error(failureMsg)
    }
  }

  def deleteFiles(filePaths: Seq[Path]): Seq[Either[Path, Path]] = {
    filePaths.map { filePath =>
      try {
        Files.delete(filePath)
        Right(filePath)
      } catch {
        case e: Throwable => Left(filePath)
      }
    }
  }

  private def writeSummaryVehicleStats(summaryVehicleStatsFile: File): immutable.HashSet[String] = {
    val columns = Seq("vehicleMilesTraveled", "vehicleHoursTraveled", "numberOfVehicles")

    val out = new BufferedWriter(new FileWriter(summaryVehicleStatsFile))
    out.write("iteration,vehicleType,")
    out.write(columns.mkString(","))
    out.newLine()

    val ignoredStats = mutable.HashSet.empty[String]
    iterationSummaryStats.zipWithIndex.foreach {
      case (stats, it) =>
        val (ignored, parsed) =
          SummaryVehicleStatsParser.splitStatsMap(stats.map(kv => (kv._1, Double2double(kv._2))), columns)

        ignoredStats ++= ignored
        parsed.foreach {
          case (vehicleType, statsValues) =>
            out.write(s"$it,$vehicleType,")
            out.write(statsValues.mkString(","))
            out.newLine()
        }
    }

    out.close()
    // because motorizedVehicleMilesTraveled and vehicleMilesTraveled contains the same data
    // so we assume that we already processed both
    immutable.HashSet[String](ignoredStats.filterNot(_.startsWith("motorizedVehicleMilesTraveled")).toSeq: _*)
  }

  private def writeSummaryStats(summaryStatsFile: File, unProcessedStats: immutable.HashSet[String]): Unit = {
    val keys = iterationSummaryStats.flatMap(_.keySet).distinct.filter(unProcessedStats.contains).sorted

    val out = new BufferedWriter(new FileWriter(summaryStatsFile))
    out.write("Iteration,")
    out.write(keys.mkString(","))
    out.newLine()

    iterationSummaryStats.zipWithIndex.foreach {
      case (stats, it) =>
        out.write(s"$it,")
        out.write(
          keys
            .map { key =>
              stats.getOrElse(key, 0)
            }
            .mkString(",")
        )
        out.newLine()
    }

    out.close()
  }

  def createSummaryStatsGraph(fileName: String, iteration: Int): Unit = {
    val fileNamePath =
      beamServices.matsimServices.getControlerIO.getOutputFilename(fileName.replaceAll("[/: ]", "_") + ".png")
    val index = fileNamePath.lastIndexOf("/")
    val outDir = new File(fileNamePath.substring(0, index) + "/summaryStats")
    val directoryName = fileName.split("_")(0)
    val numberOfGraphs: Int = 10
    val directoryKeySet = graphFileNameDirectory.filter(_._2 >= numberOfGraphs).keySet

    if (!outDir.exists()) {
      Files.createDirectories(outDir.toPath)
    }

    if (directoryKeySet.contains(directoryName)) {
      directoryKeySet foreach { file =>
        if (file.equals(directoryName)) {
          val dir = new File(outDir.getPath + "/" + file)
          if (!dir.exists()) {
            Files.createDirectories(dir.toPath)
          }
          val path = dir.getPath + fileNamePath.substring(index)
          createGraph(iteration, fileName, path)
        }
      }
    } else {
      val path = outDir.getPath + fileNamePath.substring(index)
      createGraph(iteration, fileName, path)
    }

  }

  def createGraph(iteration: Int, fileName: String, path: String): Unit = {
    val doubleOpt = iterationSummaryStats(iteration).get(fileName)
    val value: Double = doubleOpt.getOrElse(0.0).asInstanceOf[Double]

    val dataset = new DefaultCategoryDataset

    var data = summaryData.getOrElse(fileName, new mutable.TreeMap[Int, Double])
    data += (iteration      -> value)
    summaryData += fileName -> data

    val updateData = summaryData.getOrElse(fileName, new mutable.TreeMap[Int, Double])

    updateData.foreach(x => dataset.addValue(x._2, 0, x._1))

    val fileNameTokens = fileName.replaceAll("[:/ ]", "_").split("_")
    var header = StringUtils.splitByCharacterTypeCamelCase(fileNameTokens(0)).map(_.capitalize).mkString(" ")
    if (fileNameTokens.size > 1) {
      header = header + "(" + fileNameTokens.slice(1, fileNameTokens.size).mkString("_") + ")"
    }

    val chart = GraphUtils.createStackedBarChartWithDefaultSettings(
      dataset,
      header,
      "iteration",
      header,
      false
    )

    GraphUtils.saveJFreeChartAsPNG(
      chart,
      path,
      GraphsStatsAgentSimEventsListener.GRAPH_WIDTH,
      GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT
    )
  }

  private def finalizeBackgroundSkimsCreator(): Unit = {
    val timeoutForSkimmer = beamServices.beamConfig.beam.urbansim.backgroundODSkimsCreator.calculationTimeoutHours.hours
    backgroundSkimsCreator match {
      case Some(skimCreator) =>
        val abstractSkimmer = Await.result(skimCreator.getResult, timeoutForSkimmer).abstractSkimmer
        skimCreator.stop()
        val currentTravelTime = Await
          .result(beamServices.beamRouter.ask(BeamRouter.GetTravelTime), 100.seconds)
          .asInstanceOf[UpdateTravelTimeLocal]
          .travelTime
        val geoClustering = skimCreator.geoClustering

        val backgroundODSkimsCreatorConfig = beamServices.beamConfig.beam.urbansim.backgroundODSkimsCreator
        val carAndDriveTransitSkimCreator = new BackgroundSkimsCreator(
          beamServices,
          beamScenario,
          geoClustering,
          abstractSkimmer,
          currentTravelTime,
          Array(BeamMode.CAR, BeamMode.WALK),
          withTransit = backgroundODSkimsCreatorConfig.modesToBuild.drive_transit,
          buildDirectWalkRoute = false,
          buildDirectCarRoute = backgroundODSkimsCreatorConfig.modesToBuild.drive,
          calculationTimeoutHours = backgroundODSkimsCreatorConfig.calculationTimeoutHours
        )(actorSystem)
        carAndDriveTransitSkimCreator.start()
        carAndDriveTransitSkimCreator.increaseParallelismTo(Runtime.getRuntime.availableProcessors())
        try {
          val finalSkimmer = Await.result(carAndDriveTransitSkimCreator.getResult, timeoutForSkimmer).abstractSkimmer
          carAndDriveTransitSkimCreator.stop()
          finalSkimmer.writeToDisk(
            new IterationEndsEvent(beamServices.matsimServices, beamServices.matsimServices.getIterationNumber)
          )
        } catch {
          case NonFatal(ex) =>
            logger.error(
              s"Can't get the result from background skims creator or write the result to the disk: ${ex.getMessage}",
              ex
            )
        }

      case None =>
    }
  }
}
