package beam.utils

import akka.actor.ActorSystem
import beam.agentsim.events.PathTraversalEvent
import beam.agentsim.events.handling.TravelTimeGoogleStatistic
import beam.router.Modes.BeamMode
import beam.sim.BeamHelper
import beam.sim.common.SimpleGeoUtils
import beam.sim.config.BeamExecutionConfig
import beam.utils.FileUtils.using
import com.google.inject.{Injector, Provider}
import com.typesafe.scalalogging.StrictLogging
import org.matsim.analysis.{CalcLinkStats, IterationStopWatch, ScoreStats, VolumesAnalyzer}
import org.matsim.api.core.v01.Scenario
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.config.Config
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.controler.listener.ControlerListener
import org.matsim.core.controler.{MatsimServices, OutputDirectoryHierarchy}
import org.matsim.core.replanning.StrategyManager
import org.matsim.core.router.costcalculators.TravelDisutilityFactory
import org.matsim.core.router.util.{LeastCostPathCalculatorFactory, TravelDisutility, TravelTime}
import org.matsim.core.router.TripRouter
import org.matsim.core.scoring.ScoringFunctionFactory

/**
  * Run it using gradle:<br>
  * `./gradlew :execute -PmaxRAM=20 -PmainClass=beam.agentsim.events.handling.TravelTimeGoogleApp -PappArgs=["'--config', 'test/input/sf-light/sf-light-0.5k.conf', 'path/to/0.events.csv'"] -PlogbackCfg=logback.xml`
  */
object TravelTimeGoogleApp extends App with StrictLogging {

  if (args.length != 3) {
    println("Expected arguments: --config path/to/beam/config.conf path/to/0.events.csv(.gz)")
    System.exit(1)
  }

  val pathToEventFile = args(2)

  private val beamArg = Array(args(0), args(1))
  val beamHelper = new BeamHelper {}
  val (_, config) = beamHelper.prepareConfig(beamArg, isConfigArgRequired = true)
  val execCfg = beamHelper.setupBeamWithConfig(config)

  using(ActorSystem())(_.terminate())(actorSystem => processEventFile(execCfg, actorSystem))

  private def processEventFile(execCfg: BeamExecutionConfig, actorSystem: ActorSystem): Unit = {
    val statCfg = execCfg.beamConfig.beam.calibration.google.travelTimes.copy(enable = true)
    val statistic = new TravelTimeGoogleStatistic(statCfg, actorSystem, SimpleGeoUtils())

    val iteration = 0
    statistic.reset(iteration)

    logger.info(s"Reading events from $pathToEventFile")

    using(
      EventReader.fromCsvFile(
        pathToEventFile,
        event => {
          val isPTE = event.getEventType == PathTraversalEvent.EVENT_TYPE
          val isCar = BeamMode.isCar(event.getAttributes.get(PathTraversalEvent.ATTRIBUTE_MODE))
          isPTE && isCar
        }
      )
    ) { case (_, c) =>
      c.close()
    } { case (eventSeq, _) =>
      eventSeq.foreach { rawEvent =>
        val pte = PathTraversalEvent(rawEvent)
        statistic.handleEvent(pte)
      }
    }
    logger.info(s"${statistic.loadedEventNumber} events loaded")

    val controller = new OutputDirectoryHierarchy(execCfg.outputDirectory, OverwriteFileSetting.overwriteExistingFiles)
    controller.createIterationDirectory(iteration)
    statistic.notifyIterationEnds(new IterationEndsEvent(new SimplifiedMatsimServices(controller), iteration))
  }
}

class SimplifiedMatsimServices(controller: OutputDirectoryHierarchy) extends MatsimServices {
  override def getStopwatch: IterationStopWatch = ???

  override def getLinkTravelTimes: TravelTime = ???

  override def getTripRouterProvider: Provider[TripRouter] = ???

  override def createTravelDisutilityCalculator(): TravelDisutility = ???

  override def getLeastCostPathCalculatorFactory: LeastCostPathCalculatorFactory = ???

  override def getScoringFunctionFactory: ScoringFunctionFactory = ???

  override def getConfig: Config = ???

  override def getScenario: Scenario = ???

  override def getEvents: EventsManager = ???

  override def getInjector: Injector = ???

  override def getLinkStats: CalcLinkStats = ???

  override def getVolumes: VolumesAnalyzer = ???

  override def getScoreStats: ScoreStats = ???

  override def getTravelDisutilityFactory: TravelDisutilityFactory = ???

  override def getStrategyManager: StrategyManager = ???

  override def getControlerIO: OutputDirectoryHierarchy = controller

  override def addControlerListener(controlerListener: ControlerListener): Unit = ???

  override def getIterationNumber = 0
}
