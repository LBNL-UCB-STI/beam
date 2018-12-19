package beam.integration

import java.time.ZonedDateTime

import akka.actor._
import akka.testkit.{ImplicitSender, TestKit}
import beam.agentsim.agents.PersonTestUtil
import beam.agentsim.agents.choice.mode.ModeSubsidy.Subsidy
import beam.agentsim.agents.choice.mode.PtFares.FareRule
import beam.agentsim.agents.choice.mode.{ModeChoiceUniformRandom, ModeSubsidy, PtFares}
import beam.agentsim.agents.ridehail.{RideHailIterationHistory, RideHailSurgePricingManager}
import beam.agentsim.agents.vehicles.{BeamVehicle, FuelType}
import beam.router.BeamRouter
import beam.router.Modes.BeamMode
import beam.router.gtfs.FareCalculator
import beam.router.osm.TollCalculator
import beam.router.r5.DefaultNetworkCoordinator
import beam.sim.common.{GeoUtils, GeoUtilsImpl}
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.AttributesOfIndividual
import beam.sim.{BeamMobsim, BeamServices}
import beam.utils.DateUtils
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import org.matsim.api.core.v01.events.{ActivityEndEvent, Event, PersonDepartureEvent}
import org.matsim.api.core.v01.population.{Activity, Leg, Person}
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.events.{EventsManagerImpl, EventsUtils}
import org.matsim.core.scenario.ScenarioUtils
import org.matsim.vehicles.Vehicle
import org.mockito.Mockito._
import org.scalatest._
import org.scalatest.mockito.MockitoSugar
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.language.postfixOps

class SingleModeSpec
    extends TestKit(
      ActorSystem(
        "single-mode-test",
        ConfigFactory
          .load()
          .withValue("akka.test.timefactor", ConfigValueFactory.fromAnyRef(10))
      )
    )
    with WordSpecLike
    with Matchers
    with ImplicitSender
    with MockitoSugar
    with BeforeAndAfterAll
    with Inside {

  var router: ActorRef = _
  var geo: GeoUtils = _
  var scenario: Scenario = _
  var services: BeamServices = _
  var networkCoordinator: DefaultNetworkCoordinator = _
  var beamConfig: BeamConfig = _
  var tollCalculator: TollCalculator = _

  override def beforeAll: Unit = {
    val config = testConfig("test/input/sf-light/sf-light.conf")
    beamConfig = BeamConfig(config)

    val vehicleTypes = {
      val fuelTypes: TrieMap[Id[FuelType], FuelType] =
        BeamServices.readFuelTypeFile(beamConfig.beam.agentsim.agents.vehicles.beamFuelTypesFile)
      BeamServices.readBeamVehicleTypeFile(beamConfig.beam.agentsim.agents.vehicles.beamVehicleTypesFile, fuelTypes)
    }

    services = mock[BeamServices](withSettings().stubOnly())
    when(services.beamConfig).thenReturn(beamConfig)
    when(services.tazTreeMap).thenReturn(BeamServices.getTazTreeMap(beamConfig.beam.agentsim.taz.file))
    when(services.vehicleTypes).thenReturn(vehicleTypes)
    when(services.vehicles).thenReturn(TrieMap[Id[BeamVehicle], BeamVehicle]())
    when(services.agencyAndRouteByVehicleIds).thenReturn(TrieMap[Id[Vehicle], (String, String)]())
    when(services.ptFares).thenReturn(PtFares(Map[String, List[FareRule]]()))
    when(services.privateVehicles).thenReturn {
      BeamServices.readVehiclesFile(beamConfig.beam.agentsim.agents.vehicles.beamVehiclesFile, vehicleTypes)
    }

    geo = new GeoUtilsImpl(services)
    when(services.geo).thenReturn(geo)
    when(services.dates).thenReturn(
      DateUtils(
        ZonedDateTime.parse(beamConfig.beam.routing.baseDate).toLocalDateTime,
        ZonedDateTime.parse(beamConfig.beam.routing.baseDate)
      )
    )
    when(services.vehicles).thenReturn(new TrieMap[Id[BeamVehicle], BeamVehicle])
    when(services.modeChoiceCalculatorFactory)
      .thenReturn((_: AttributesOfIndividual) => new ModeChoiceUniformRandom(services))
    val personRefs = TrieMap[Id[Person], ActorRef]()
    when(services.personRefs).thenReturn(personRefs)
    when(services.modeSubsidies).thenReturn(ModeSubsidy(Map[BeamMode, List[Subsidy]]()))
    networkCoordinator = DefaultNetworkCoordinator(beamConfig)
    networkCoordinator.loadNetwork()
    networkCoordinator.convertFrequenciesToTrips()

    val fareCalculator = new FareCalculator(beamConfig.beam.routing.r5.directory)
    tollCalculator = new TollCalculator(beamConfig)
    val matsimConfig = new MatSimBeamConfigBuilder(config).buildMatSamConf()
    scenario = ScenarioUtils.loadScenario(matsimConfig)
    scenario.getPopulation.getPersons.values.asScala.foreach(PersonTestUtil.putDefaultBeamAttributes)
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
    when(services.beamRouter).thenReturn(router)
  }

  override def afterAll: Unit = {
    shutdown()
    router = null
    geo = null
    scenario = null
    services = null
    networkCoordinator = null
    beamConfig = null
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
      //          eventsManager.addHandler(
      //            new BasicEventHandler {
      //              override def handleEvent(event: Event): Unit = {
      //                event match {
      //                  case event: PathTraversalEvent if event.getAttributes.get("amount_paid").toDouble != 0.0 =>
      //                    println(event)
      //                  case _ =>
      //                }
      //              }
      //            }
      //          )
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
    }
  }

}
