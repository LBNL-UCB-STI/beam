package beam.integration

import java.io.File

import akka.actor._
import beam.agentsim.agents.PersonTestUtil
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.agentsim.agents.ridehail.{RideHailIterationHistory, RideHailSurgePricingManager}
import beam.agentsim.events.PathTraversalEvent
import beam.router.BeamRouter
import beam.router.Modes.BeamMode
import beam.router.gtfs.FareCalculator
import beam.router.osm.TollCalculator
import beam.router.r5.DefaultNetworkCoordinator
import beam.sim.common.{GeoUtils, GeoUtilsImpl}
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.{BeamMobsim, BeamServices, BeamServicesImpl}
import beam.utils.TestConfigUtils.{testConfig, testOutputDir}
import beam.utils.{NetworkHelper, NetworkHelperImpl}
import com.google.inject.util.Providers
import com.google.inject.{AbstractModule, Guice, Injector, Provider}
import com.typesafe.config.ConfigFactory
import org.matsim.analysis.{CalcLinkStats, IterationStopWatch, ScoreStats, VolumesAnalyzer}
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.events.{ActivityEndEvent, Event, PersonDepartureEvent, PersonEntersVehicleEvent}
import org.matsim.api.core.v01.population.{Activity, Leg}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.config.Config
import org.matsim.core.controler.listener.ControlerListener
import org.matsim.core.controler.{ControlerI, MatsimServices, OutputDirectoryHierarchy}
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.events.{EventsManagerImpl, EventsUtils}
import org.matsim.core.replanning.StrategyManager
import org.matsim.core.router.TripRouter
import org.matsim.core.router.costcalculators.TravelDisutilityFactory
import org.matsim.core.router.util.{LeastCostPathCalculatorFactory, TravelDisutility, TravelTime}
import org.matsim.core.scenario.ScenarioUtils
import org.matsim.core.scoring.ScoringFunctionFactory
import org.scalatest._
import org.scalatest.mockito.MockitoSugar

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.language.postfixOps

class MatsimServicesMock(override val getControlerIO: OutputDirectoryHierarchy, override val getScenario: Scenario)
    extends MatsimServices {
  override def getStopwatch: IterationStopWatch = null
  override def getLinkTravelTimes: TravelTime = null
  override def getTripRouterProvider: Provider[TripRouter] = null
  override def createTravelDisutilityCalculator(): TravelDisutility = null
  override def getLeastCostPathCalculatorFactory: LeastCostPathCalculatorFactory = null
  override def getScoringFunctionFactory: ScoringFunctionFactory = null
  override def getConfig: Config = null
  override def getEvents: EventsManager = null
  override def getInjector: Injector = null
  override def getLinkStats: CalcLinkStats = null
  override def getVolumes: VolumesAnalyzer = null
  override def getScoreStats: ScoreStats = null
  override def getTravelDisutilityFactory: TravelDisutilityFactory = null
  override def getStrategyManager: StrategyManager = null
  override def addControlerListener(controlerListener: ControlerListener): Unit = {}
  override def getIterationNumber: Integer = null
}

class SingleModeSpec extends WordSpecLike with Matchers with MockitoSugar with BeforeAndAfterEach with Inside {

  private val BASE_PATH = new File("").getAbsolutePath
  private val OUTPUT_DIR_PATH = BASE_PATH + "/" + testOutputDir + "single-mode-test"

  val config = ConfigFactory
    .parseString("""akka.test.timefactor = 10""")
    .withFallback(testConfig("test/input/sf-light/sf-light.conf").resolve())

  lazy val beamCfg = BeamConfig(config)
  lazy val matsimConfig = new MatSimBeamConfigBuilder(config).buildMatSamConf()
  lazy val scenario = ScenarioUtils.loadScenario(matsimConfig)
  lazy val geoUtil = new GeoUtilsImpl(beamCfg)
  lazy val networkCoordinator = {
    val nc = DefaultNetworkCoordinator(beamCfg)
    nc.loadNetwork()
    nc.convertFrequenciesToTrips()
    nc
  }
  lazy val networkHelper = new NetworkHelperImpl(networkCoordinator.network)

  lazy val fareCalculator = new FareCalculator(beamCfg.beam.routing.r5.directory)
  lazy val tollCalculator = new TollCalculator(beamCfg)

  lazy val outputDirectoryHierarchy: OutputDirectoryHierarchy = {
    val overwriteExistingFiles =
      OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles
    val odh =
      new OutputDirectoryHierarchy(OUTPUT_DIR_PATH, overwriteExistingFiles)
    odh.createIterationDirectory(0)
    odh
  }
  lazy val matsimSvc: MatsimServices = new MatsimServicesMock(outputDirectoryHierarchy, scenario)

  var router: ActorRef = _
  var services: BeamServices = _
  var nextId: Int = 0
  var system: ActorSystem = _

  override def beforeEach: Unit = {
    // Create brand new Actor system every time (just to make sure that the same actor names can be reused)
    system = ActorSystem("single-mode-test-" + nextId, config)
    nextId += 1

    val injector = Guice.createInjector(new AbstractModule() {
      protected def configure(): Unit = {
        bind(classOf[BeamConfig]).toInstance(beamCfg)
        bind(classOf[GeoUtils]).toInstance(geoUtil)
        bind(classOf[NetworkHelper]).toInstance(networkHelper)
        bind(classOf[ControlerI]).toProvider(Providers.of(null))
      }
    })

    services = new BeamServicesImpl(injector)
    services.matsimServices = matsimSvc
    services.modeChoiceCalculatorFactory = ModeChoiceCalculator(
      services.beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass,
      services
    )

    scenario.getPopulation.getPersons.values.asScala
      .foreach(p => PersonTestUtil.putDefaultBeamAttributes(p, BeamMode.allTripModes))

    router = system.actorOf(
      BeamRouter.props(
        services,
        networkCoordinator.transportNetwork,
        networkCoordinator.network,
        scenario,
        new EventsManagerImpl(),
        scenario.getTransitVehicles,
        fareCalculator,
        tollCalculator
      ),
      "router"
    )
    services.beamRouter = router
  }

  override def afterEach: Unit = {
    system.terminate()
    router = null
    services = null
  }

  "The agentsim" must {
    "let everybody walk when their plan says so" in {
      scenario.getPopulation.getPersons
        .values()
        .forEach { person =>
          {
            person.getSelectedPlan.getPlanElements.asScala.collect {
              case leg: Leg =>
                leg.setMode("walk")
            }
          }
        }
      val events = mutable.ListBuffer[Event]()
      val eventsManager = EventsUtils.createEventsManager()
      eventsManager.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            event match {
              case event: PersonDepartureEvent =>
                events += event
              case _ =>
            }
          }
        }
      )
      val mobsim = new BeamMobsim(
        services,
        networkCoordinator.transportNetwork,
        tollCalculator,
        scenario,
        eventsManager,
        system,
        new RideHailSurgePricingManager(services),
        new RideHailIterationHistory()
      )
      mobsim.run()
      events.foreach {
        case event: PersonDepartureEvent =>
          assert(event.getLegMode == "walk" || event.getLegMode == "be_a_tnc_driver")
      }
    }

    "let everybody take transit when their plan says so" in {
      scenario.getPopulation.getPersons
        .values()
        .forEach { person =>
          person.getSelectedPlan.getPlanElements.asScala.collect {
            case leg: Leg =>
              leg.setMode("walk_transit")
          }
        }
      val events = mutable.ListBuffer[Event]()
      val eventsManager = EventsUtils.createEventsManager()
      eventsManager.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            event match {
              case event: PersonDepartureEvent =>
                events += event
              case _ =>
            }
          }
        }
      )
      val mobsim = new BeamMobsim(
        services,
        networkCoordinator.transportNetwork,
        tollCalculator,
        scenario,
        eventsManager,
        system,
        new RideHailSurgePricingManager(services),
        new RideHailIterationHistory()
      )
      mobsim.run()
      events.foreach {
        case event: PersonDepartureEvent =>
          assert(
            event.getLegMode == "walk" || event.getLegMode == "walk_transit" || event.getLegMode == "be_a_tnc_driver"
          )
      }
    }

    "let everybody take drive_transit when their plan says so" in {
      // Here, we only set the mode for the first leg of each tour -- prescribing a mode for the tour,
      // but not for individual legs except the first one.
      // We want to make sure that our car is returned home.
      scenario.getPopulation.getPersons
        .values()
        .forEach { person =>
          {
            val newPlanElements = person.getSelectedPlan.getPlanElements.asScala.collect {
              case activity: Activity if activity.getType == "Home" =>
                Seq(activity, scenario.getPopulation.getFactory.createLeg("drive_transit"))
              case activity: Activity =>
                Seq(activity)
              case leg: Leg =>
                Nil
            }.flatten
            if (newPlanElements.last.isInstanceOf[Leg]) {
              newPlanElements.remove(newPlanElements.size - 1)
            }
            person.getSelectedPlan.getPlanElements.clear()
            newPlanElements.foreach {
              case activity: Activity =>
                person.getSelectedPlan.addActivity(activity)
              case leg: Leg =>
                person.getSelectedPlan.addLeg(leg)
            }
          }
        }
      val events = mutable.ListBuffer[Event]()
      val eventsManager = EventsUtils.createEventsManager()
      eventsManager.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            event match {
              case event @ (_: PersonDepartureEvent | _: ActivityEndEvent) =>
                events += event
              case _ =>
            }
          }
        }
      )
      val mobsim = new BeamMobsim(
        services,
        networkCoordinator.transportNetwork,
        tollCalculator,
        scenario,
        eventsManager,
        system,
        new RideHailSurgePricingManager(services),
        new RideHailIterationHistory()
      )
      mobsim.run()
      events.collect {
        case event: PersonDepartureEvent =>
          // drive_transit can fail -- maybe I don't have a car
          assert(
            event.getLegMode == "walk" || event.getLegMode == "walk_transit" || event.getLegMode == "drive_transit" || event.getLegMode == "be_a_tnc_driver"
          )
      }
      val eventsByPerson = events.groupBy(_.getAttributes.get("person"))
      val filteredEventsByPerson = eventsByPerson.filter {
        _._2
          .filter(_.isInstanceOf[ActivityEndEvent])
          .sliding(2)
          .exists(
            pair => pair.forall(activity => activity.asInstanceOf[ActivityEndEvent].getActType != "Home")
          )
      }
      eventsByPerson.map {
        _._2.span {
          case event: ActivityEndEvent if event.getActType == "Home" =>
            true
          case _ =>
            false
        }
      }
      // TODO: Test that what can be printed with the line below makes sense (chains of modes)
      //      filteredEventsByPerson.map(_._2.mkString("--\n","\n","--\n")).foreach(print(_))
    }

    "let everybody drive when their plan says so" in {
      scenario.getPopulation.getPersons
        .values()
        .forEach { person =>
          {
            person.getSelectedPlan.getPlanElements.asScala.collect {
              case leg: Leg =>
                leg.setMode("car")
            }
          }
        }
      val eventsManager = EventsUtils.createEventsManager()
      val events = mutable.ListBuffer[Event]()
      eventsManager.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            event match {
              case event @ (_: PersonDepartureEvent | _: ActivityEndEvent | _: PathTraversalEvent |
                  _: PersonEntersVehicleEvent) =>
                events += event
              case _ =>
            }
          }
        }
      )

      val mobsim = new BeamMobsim(
        services,
        networkCoordinator.transportNetwork,
        tollCalculator,
        scenario,
        eventsManager,
        system,
        new RideHailSurgePricingManager(services),
        new RideHailIterationHistory()
      )
      mobsim.run()
      events.collect {
        case event: PersonDepartureEvent =>
          // Wr still get some failing car routes.
          // TODO: Find root cause, fix, and remove "walk" here.
          // See SfLightRouterSpec.
          assert(event.getLegMode == "walk" || event.getLegMode == "car" || event.getLegMode == "be_a_tnc_driver")
      }
    }
  }

}
