package beam.sim

import java.io.{BufferedWriter, File, FileWriter}
import java.nio.file.{Files, Paths}
import java.util.concurrent.TimeUnit

import org.apache.commons.lang3.text.WordUtils
import akka.actor.{ActorRef, ActorSystem, Identify}
import akka.pattern.ask
import akka.util.Timeout
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.agentsim.agents.ridehail.{RideHailIterationHistoryActor, TNCIterationsStatsCollector}
import beam.analysis.IterationStatsProvider
import beam.analysis.plots.modality.ModalityStyleStats
import beam.analysis.plots.GraphsStatsAgentSimEventsListener
import beam.analysis.via.ExpectedMaxUtilityHeatMap
import beam.physsim.jdeqsim.AgentSimToPhysSimPlanConverter
import beam.router.BeamRouter
import beam.router.gtfs.FareCalculator
import beam.router.osm.TollCalculator
import beam.sim.metrics.MetricsPrinter.{Print, Subscribe}
import beam.sim.metrics.{MetricsPrinter, MetricsSupport}
import beam.utils.DebugLib
import beam.utils.scripts.PopulationWriterCSV
import com.conveyal.r5.transit.TransportNetwork
import com.google.common.base.CaseFormat
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.io.FileUtils
import org.matsim.api.core.v01.Scenario
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.events.{ControlerEvent, IterationEndsEvent, ShutdownEvent, StartupEvent}
import org.matsim.core.controler.listener.{IterationEndsListener, ShutdownListener, StartupListener}
import org.matsim.vehicles.VehicleCapacity

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

class BeamSim @Inject()(
                         private val actorSystem: ActorSystem,
                         private val transportNetwork: TransportNetwork,
                         private val beamServices: BeamServices,
                         private val eventsManager: EventsManager,
                         private val scenario: Scenario,
                         private val beamOutputDataDescriptionGenerator: BeamOutputDataDescriptionGenerator,
                       ) extends StartupListener
  with IterationEndsListener
  with ShutdownListener
  with LazyLogging
  with MetricsSupport {

  private var agentSimToPhysSimPlanConverter: AgentSimToPhysSimPlanConverter = _
  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)

  private var createGraphsFromEvents: GraphsStatsAgentSimEventsListener = _
  private var modalityStyleStats: ModalityStyleStats = _
  private var expectedDisutilityHeatMapDataCollector: ExpectedMaxUtilityHeatMap = _
  private var rideHailIterationHistoryActor: ActorRef = _

  private var tncIterationsStatsCollector: TNCIterationsStatsCollector = _
  val rideHailIterationHistoryActorName = "rideHailIterationHistoryActor"
  val iterationStatsProviders: ListBuffer[IterationStatsProvider] = new ListBuffer()
  val iterationSummaryStats: ListBuffer[Map[java.lang.String, java.lang.Double]] = ListBuffer()
  var metricsPrinter: ActorRef = actorSystem.actorOf(MetricsPrinter.props())

  override def notifyStartup(event: StartupEvent): Unit = {
    beamServices.modeChoiceCalculatorFactory = ModeChoiceCalculator(
      beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass,
      beamServices
    )

    metricsPrinter ! Subscribe("counter", "**")
    metricsPrinter ! Subscribe("histogram", "**")

    val fareCalculator = new FareCalculator(beamServices.beamConfig.beam.routing.r5.directory)
    val tollCalculator = new TollCalculator(beamServices.beamConfig, beamServices.beamConfig.beam.routing.r5.directory)
    beamServices.beamRouter = actorSystem.actorOf(
      BeamRouter.props(
        beamServices,
        transportNetwork,
        scenario.getNetwork,
        scenario,
        eventsManager,
        scenario.getTransitVehicles,
        fareCalculator,
        tollCalculator
      ),
      "router"
    )
    Await.result(beamServices.beamRouter ? Identify(0), timeout.duration)

    /*    if(null != beamServices.beamConfig.beam.agentsim.taz.file && !beamServices.beamConfig.beam.agentsim.taz.file.isEmpty)
          beamServices.taz = TAZTreeMap.fromCsv(beamServices.beamConfig.beam.agentsim.taz.file)*/

    beamServices.matsimServices = event.getServices

    if (!beamServices.beamConfig.beam.physsim.skipPhysSim) {
      agentSimToPhysSimPlanConverter = new AgentSimToPhysSimPlanConverter(
        eventsManager,
        transportNetwork,
        event.getServices.getControlerIO,
        scenario,
        beamServices
      )
      iterationStatsProviders += agentSimToPhysSimPlanConverter
    }

    createGraphsFromEvents = new GraphsStatsAgentSimEventsListener(
      eventsManager,
      event.getServices.getControlerIO,
      beamServices,
      beamServices.beamConfig
    )
    iterationStatsProviders += createGraphsFromEvents
    modalityStyleStats = new ModalityStyleStats()
    expectedDisutilityHeatMapDataCollector = new ExpectedMaxUtilityHeatMap(
      eventsManager,
      scenario.getNetwork,
      event.getServices.getControlerIO,
      beamServices.beamConfig.beam.outputs.writeEventsInterval
    )

    rideHailIterationHistoryActor = actorSystem.actorOf(
      RideHailIterationHistoryActor.props(eventsManager, beamServices, transportNetwork),
      rideHailIterationHistoryActorName
    )
    tncIterationsStatsCollector = new TNCIterationsStatsCollector(
      eventsManager,
      beamServices,
      rideHailIterationHistoryActor,
      transportNetwork
    )

    // report inconsistencies in output:
    //new RideHailDebugEventHandler(eventsManager)
  }

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    if (beamServices.beamConfig.beam.debug.debugEnabled)
      logger.info(DebugLib.gcAndGetMemoryLogMessage("notifyIterationEnds.start (after GC): "))

    val outputGraphsFuture = Future {
      if ("ModeChoiceLCCM".equals(beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass)) {
        modalityStyleStats.processData(scenario.getPopulation, event)
        modalityStyleStats.buildModalityStyleGraph()
      }
      createGraphsFromEvents.createGraphs(event)
      val interval = beamServices.beamConfig.beam.outputs.writePlansInterval
      if (interval > 0 && event.getIteration % interval == 0) {
        PopulationWriterCSV(event.getServices.getScenario.getPopulation).write(
          event.getServices.getControlerIO
            .getIterationFilename(event.getIteration, "population.csv.gz")
        )
      }

      iterationSummaryStats += iterationStatsProviders
        .flatMap(_.getSummaryStats.asScala)
        .toMap

      val summaryStatsFile = Paths.get(event.getServices.getControlerIO.getOutputFilename("summaryStats.csv")).toFile
      writeSummaryStats(summaryStatsFile)
      // rideHailIterationHistoryActor ! CollectRideHailStats
      tncIterationsStatsCollector
        .tellHistoryToRideHailIterationHistoryActorAndReset()
    }

    if (beamServices.beamConfig.beam.physsim.skipPhysSim) {
      Await.result(Future.sequence(List(outputGraphsFuture)), Duration.Inf)
    } else {
      val physsimFuture = Future {
        agentSimToPhysSimPlanConverter.startPhysSim(event)
      }

      // executing code blocks parallel
      Await.result(Future.sequence(List(outputGraphsFuture, physsimFuture)), Duration.Inf)
    }

    if (beamServices.beamConfig.beam.debug.debugEnabled)
      logger.info(DebugLib.gcAndGetMemoryLogMessage("notifyIterationEnds.end (after GC): "))
    stopMeasuringIteration()

    val persons = scenario.getPopulation.getPersons.values().asScala
    logger.info(
      "Iteration {} - average number of plans per agent: {}",
      event.getIteration,
      persons.map(_.getPlans.size()).sum.toFloat / persons.size
    )
    //    Tracer.currentContext.finish()
    metricsPrinter ! Print(
      Seq(
        "r5-plans-count"
      ),
      Nil
    )
    //rename output files generated by matsim to follow the standard naming convention of camel case
    renameGeneratedOutputFiles(event)
    logger.info("Ending Iteration")
  }

  override def notifyShutdown(event: ShutdownEvent): Unit = {

    val firstIteration = beamServices.beamConfig.matsim.modules.controler.firstIteration
    val lastIteration = beamServices.beamConfig.matsim.modules.controler.lastIteration

    logger.info("Generating html page to compare graphs (across all iterations)")
    BeamGraphComparator.generateGraphComparisonHtmlPage(event, firstIteration, lastIteration)
    beamOutputDataDescriptionGenerator.generateDescriptors(event)

    Await.result(actorSystem.terminate(), Duration.Inf)
    logger.info("Actor system shut down")

    // remove output files which are not ready for release yet (enable again after Jan 2018)
    val outputFilesToDelete = Array(
      "traveldistancestats.txt",
      "traveldistancestats.png",
      "tmp",
      "modestats.txt",
      "modestats.png"
    )

    //rename output files generated by matsim to follow the standard naming convention of camel case
    renameGeneratedOutputFiles(event)
    outputFilesToDelete.foreach(deleteOutputFile)
    createGraphsFromEvents.notifyShutdown(event)

    def deleteOutputFile(fileName: String) = {
      logger.debug(s"deleting output file: $fileName")
      Files.deleteIfExists(Paths.get(event.getServices.getControlerIO.getOutputFilename(fileName)))
    }
  }

  private def writeSummaryStats(summaryStatsFile: File): Unit = {
    val keys = iterationSummaryStats.flatMap(_.keySet).distinct.sorted

    val out = new BufferedWriter(new FileWriter(summaryStatsFile))
    out.write("Iteration,")
    out.write(keys.mkString(","))
    out.newLine()

    iterationSummaryStats.zipWithIndex.foreach {
      case (stats, it) =>
        out.write(s"$it,")
        out.write(keys.map(stats.getOrElse(_, "")).mkString(","))
        out.newLine()
    }

    out.close()
  }

  /**
    * Rename output files generated by libraries to match the standard naming convention of camel case.
    * @param event Any controller event
    */
  private def renameGeneratedOutputFiles(event: ControlerEvent): Unit = {
    val filesToBeRenamed: Array[File] = event match {
      case _ if event.isInstanceOf[IterationEndsEvent] =>
        val iterationEvent = event.asInstanceOf[IterationEndsEvent]
        val outputIterationFileNameRegex = List(s"legHistogram(.*)", "experienced(.*)")
        // filter files that match output file name regex and are to be renamed
        FileUtils
          .getFile(new File(event.getServices.getControlerIO.getIterationPath(iterationEvent.getIteration)))
          .listFiles()
          .filter(
            f =>
              outputIterationFileNameRegex.exists(
                f.getName
                  .replace(event.getServices.getIterationNumber.toInt + ".", "")
                  .matches(_)
            )
          )
      case _ if event.isInstanceOf[ShutdownEvent] =>
        val shutdownEvent = event.asInstanceOf[ShutdownEvent]
        val outputFileNameRegex = List("output(.*)")
        // filter files that match output file name regex and are to be renamed
        FileUtils
          .getFile(new File(shutdownEvent.getServices.getControlerIO.getOutputPath))
          .listFiles()
          .filter(f => outputFileNameRegex.exists(f.getName.matches(_)))
    }
    filesToBeRenamed
      .foreach { file =>
        //rename each file to follow the camel case
        val newFile = FileUtils.getFile(
          file.getAbsolutePath.replace(
            file.getName,
            WordUtils
              .uncapitalize(file.getName.split("_").map(_.capitalize).mkString(""))
          )
        )
        logger.info(s"Renaming file - ${file.getName} to follow camel case notation : " + newFile.getAbsoluteFile)
        try {
          if (file != newFile && !newFile.exists()) {
            file.renameTo(newFile)
          }
        } catch {
          case e: Exception =>
            logger.error(s"Error while renaming file - ${file.getName} to ${newFile.getName}", e)
        }
      }
  }
}
