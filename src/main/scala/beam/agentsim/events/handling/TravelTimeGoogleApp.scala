package beam.agentsim.events.handling

import akka.actor.ActorSystem
import beam.agentsim.events.PathTraversalEvent
import beam.router.Modes.BeamMode
import beam.sim.BeamHelper
import beam.sim.common.SimpleGeoUtils
import beam.sim.config.BeamExecutionConfig
import beam.utils.EventReader
import beam.utils.FileUtils.using
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.controler.listener.ControlerListener
import org.matsim.core.controler.{MatsimServices, OutputDirectoryHierarchy}

/**
  * Run it using gradle:<br>
  *`./gradlew :execute -PmaxRAM=20 -PmainClass=beam.physsim.jdeqsim.JDEQSimRunnerApp \
  * -PappArgs=["'--config', 'test/input/sf-light/sf-light-0.5k.conf'", 'path/to/0.events.csv'] \
  * -PlogbackCfg=logback.xml`
  */
object TravelTimeGoogleApp extends App {

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
    val statCfg = execCfg.beamConfig.beam.calibration.google.travelTimes
    val statistic = new TravelTimeGoogleStatistic(statCfg, actorSystem, SimpleGeoUtils())

    val iteration = 0
    statistic.reset(iteration)

    using(
      EventReader.fromCsvFile(
        pathToEventFile,
        event =>
          event.getEventType == PathTraversalEvent.EVENT_TYPE && event.getAttributes
            .get(PathTraversalEvent.ATTRIBUTE_MODE) == BeamMode.CAR.value
      )
    ) {
      case (_, c) => c.close()
    } {
      case (eventSeq, _) =>
        eventSeq.map(PathTraversalEvent(_)).foreach(statistic.handleEvent)
    }

    val controller = new OutputDirectoryHierarchy(execCfg.outputDirectory, OverwriteFileSetting.overwriteExistingFiles)
    controller.createIterationDirectory(iteration)
    statistic.notifyIterationEnds(new IterationEndsEvent(new SimplifiedMatsimServices(controller), iteration))
  }
}

class SimplifiedMatsimServices(controller: OutputDirectoryHierarchy) extends MatsimServices {
  override def getStopwatch = ???

  override def getLinkTravelTimes = ???

  override def getTripRouterProvider = ???

  override def createTravelDisutilityCalculator() = ???

  override def getLeastCostPathCalculatorFactory = ???

  override def getScoringFunctionFactory = ???

  override def getConfig = ???

  override def getScenario = ???

  override def getEvents = ???

  override def getInjector = ???

  override def getLinkStats = ???

  override def getVolumes = ???

  override def getScoreStats = ???

  override def getTravelDisutilityFactory = ???

  override def getStrategyManager = ???

  override def getControlerIO = controller

  override def addControlerListener(controlerListener: ControlerListener): Unit = ???

  override def getIterationNumber = 0
}
