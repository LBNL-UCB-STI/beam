package beam.integration

import akka.actor._
import akka.testkit.TestKitBase
import beam.agentsim.agents.PersonTestUtil
import beam.agentsim.agents.ridehail.{RideHailIterationHistory, RideHailSurgePricingManager}
import beam.agentsim.events.PathTraversalEvent
import beam.replanning.ModeIterationPlanCleaner
import beam.router.Modes.BeamMode
import beam.router.RouteHistory
import beam.sflight.RouterForTest
import beam.sim.common.GeoUtilsImpl
import beam.sim.{BeamHelper, BeamMobsim, RideHailFleetInitializerProvider}
import beam.utils.{MathUtils, SimRunnerForTest}
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.events.{ActivityEndEvent, Event, PersonDepartureEvent, PersonEntersVehicleEvent}
import org.matsim.api.core.v01.population.{Activity, Leg}
import org.matsim.core.events.handler.BasicEventHandler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.language.postfixOps

class SingleModeSpec
    extends AnyWordSpecLike
    with TestKitBase
    with SimRunnerForTest
    with RouterForTest
    with BeamHelper
    with Matchers {

  def config: com.typesafe.config.Config =
    ConfigFactory
      .parseString("""akka.beam.sim.test.timefactor = 10""")
      .withFallback(testConfig("beam.sim.test/input/sf-light/sf-light.conf").resolve())

  def outputDirPath: String = basePath + "/" + testOutputDir + "single-mode-beam.sim.test"

  lazy implicit val system: ActorSystem = ActorSystem("SingleModeSpec", config)

  "The agentsim" must {
    "let everybody walk when their plan says so" in {
      scenario.getPopulation.getPersons.values.asScala
        .foreach(p => PersonTestUtil.putDefaultBeamAttributes(p, BeamMode.allModes))
      scenario.getPopulation.getPersons
        .values()
        .forEach { person =>
          {
            person.getSelectedPlan.getPlanElements.asScala.collect { case leg: Leg =>
              leg.setMode("walk")
            }
          }
        }
      val events = mutable.ListBuffer[Event]()
      services.matsimServices.getEvents.addHandler(
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
        beamScenario,
        beamScenario.transportNetwork,
        services.tollCalculator,
        scenario,
        services.matsimServices.getEvents,
        system,
        new RideHailSurgePricingManager(services),
        new RideHailIterationHistory(),
        new RouteHistory(services.beamConfig),
        new GeoUtilsImpl(services.beamConfig),
        new ModeIterationPlanCleaner(beamConfig, scenario),
        services.networkHelper,
        new RideHailFleetInitializerProvider(services, beamScenario, scenario)
      )
      mobsim.run()

      assert(events.nonEmpty)
      val personDepartureEvents = events.collect { case event: PersonDepartureEvent => event }
      personDepartureEvents should not be empty
      val regularPersonEvents = filterOutProfessionalDriversAndCavs(personDepartureEvents)
      regularPersonEvents.map(_.getLegMode) should contain only "walk"
    }

    "let everybody take transit when their plan says so" in {
      scenario.getPopulation.getPersons.values.asScala
        .foreach(p => PersonTestUtil.putDefaultBeamAttributes(p, BeamMode.allModes))
      scenario.getPopulation.getPersons
        .values()
        .forEach { person =>
          person.getSelectedPlan.getPlanElements.asScala.collect { case leg: Leg =>
            leg.setMode("walk_transit")
          }
        }
      val events = mutable.ListBuffer[Event]()
      services.matsimServices.getEvents.addHandler(
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
        beamScenario,
        beamScenario.transportNetwork,
        services.tollCalculator,
        scenario,
        services.matsimServices.getEvents,
        system,
        new RideHailSurgePricingManager(services),
        new RideHailIterationHistory(),
        new RouteHistory(services.beamConfig),
        new GeoUtilsImpl(services.beamConfig),
        new ModeIterationPlanCleaner(beamConfig, scenario),
        services.networkHelper,
        new RideHailFleetInitializerProvider(services, beamScenario, scenario)
      )
      mobsim.run()

      assert(events.nonEmpty)

      val personDepartureEvents = events.collect { case event: PersonDepartureEvent => event }
      personDepartureEvents should not be empty
      val regularPersonEvents = filterOutProfessionalDriversAndCavs(personDepartureEvents)
      val (walkTransit, others) = regularPersonEvents.map(_.getLegMode).partition(_ == "walk_transit")
      others.size should be < (0.02 * walkTransit.size).toInt
    }

    "let everybody take drive_transit when their plan says so" in {
      scenario.getPopulation.getPersons.values.asScala
        .foreach(p => PersonTestUtil.putDefaultBeamAttributes(p, BeamMode.allModes))
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
                Seq(activity, scenario.getPopulation.getFactory.createLeg(""))
              case _: Leg => Nil
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
      services.matsimServices.getEvents.addHandler(
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
        beamScenario,
        beamScenario.transportNetwork,
        services.tollCalculator,
        scenario,
        services.matsimServices.getEvents,
        system,
        new RideHailSurgePricingManager(services),
        new RideHailIterationHistory(),
        new RouteHistory(services.beamConfig),
        new GeoUtilsImpl(services.beamConfig),
        new ModeIterationPlanCleaner(beamConfig, scenario),
        services.networkHelper,
        new RideHailFleetInitializerProvider(services, beamScenario, scenario)
      )
      mobsim.run()

      assert(events.nonEmpty)
      val personDepartureEvents = events.collect { case event: PersonDepartureEvent => event }
      personDepartureEvents should not be empty
      val regularPersonEvents = filterOutProfessionalDriversAndCavs(personDepartureEvents)
      val (driveTransit, others) = regularPersonEvents.map(_.getLegMode).partition(_ == "drive_transit")
      //router gives too little 'drive transit' trips, most of the persons chooses 'car' in this case
      withClue("When transit is available majority of agents should use drive_transit") {
        others.count(_ == "walk_transit") should be < MathUtils.doubleToInt(0.2 * driveTransit.size)
      }

      val eventsByPerson = events.groupBy(_.getAttributes.get("person"))

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
      scenario.getPopulation.getPersons.values.asScala
        .foreach(p => PersonTestUtil.putDefaultBeamAttributes(p, BeamMode.allModes))
      scenario.getPopulation.getPersons
        .values()
        .forEach { person =>
          {
            person.getSelectedPlan.getPlanElements.asScala.collect { case leg: Leg =>
              leg.setMode("car")
            }
          }
        }
      val events = mutable.ListBuffer[Event]()
      services.matsimServices.getEvents.addHandler(
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
        beamScenario,
        beamScenario.transportNetwork,
        services.tollCalculator,
        scenario,
        services.matsimServices.getEvents,
        system,
        new RideHailSurgePricingManager(services),
        new RideHailIterationHistory(),
        new RouteHistory(services.beamConfig),
        new GeoUtilsImpl(services.beamConfig),
        new ModeIterationPlanCleaner(beamConfig, scenario),
        services.networkHelper,
        new RideHailFleetInitializerProvider(services, beamScenario, scenario)
      )
      mobsim.run()

      assert(events.nonEmpty)
      val personDepartureEvents = events.collect { case event: PersonDepartureEvent => event }
      personDepartureEvents should not be empty
      val regularPersonEvents = filterOutProfessionalDriversAndCavs(personDepartureEvents)
      val othersCount = regularPersonEvents.count(_.getLegMode != "car")
      withClue("Majority of agents should use cars. Other modes take place when no car available.") {
        othersCount should be < MathUtils.doubleToInt(0.02 * regularPersonEvents.size)
      }
    }
  }

  private def filterOutProfessionalDriversAndCavs(personDepartureEvents: ListBuffer[PersonDepartureEvent]) = {
    personDepartureEvents.filterNot(event =>
      event.getLegMode == "be_a_tnc_driver" || event.getLegMode == "be_a_household_cav_driver" || event.getLegMode == "be_a_transit_driver" || event.getLegMode == "cav"
    )
  }
}
