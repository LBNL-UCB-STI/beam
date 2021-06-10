package beam.integration

import akka.actor._
import akka.testkit.TestKitBase
import beam.agentsim.agents.PersonTestUtil
import beam.agentsim.agents.ridehail.{RideHailIterationHistory, RideHailSurgePricingManager}
import beam.agentsim.events.{ModeChoiceEvent, PathTraversalEvent}
import beam.replanning.ModeIterationPlanCleaner
import beam.router.Modes.BeamMode
import beam.router.RouteHistory
import beam.sflight.RouterForTest
import beam.sim.common.GeoUtilsImpl
import beam.sim.{BeamHelper, BeamMobsim, RideHailFleetInitializerProvider}
import beam.utils.SimRunnerForTest
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.junit.Assert
import org.matsim.api.core.v01.events.{
  ActivityEndEvent,
  Event,
  PersonArrivalEvent,
  PersonDepartureEvent,
  PersonEntersVehicleEvent
}
import org.matsim.api.core.v01.population.{Activity, Leg}
import org.matsim.core.events.handler.BasicEventHandler
import org.scalatest._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.language.postfixOps

class BikeTransitModeSpec
    extends AnyWordSpecLike
    with TestKitBase
    with SimRunnerForTest
    with RouterForTest
    with BeamHelper
    with Matchers {

  def config: com.typesafe.config.Config =
    ConfigFactory
      .parseString("""
          |akka.test.timefactor = 10
          |beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.drive_transit_intercept = 0
          |beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.bike_intercept = 10
          |beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.bike_transit_intercept = 20
          |""".stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf").resolve())

  def outputDirPath: String = basePath + "/" + testOutputDir + "transit-mode-test"

  lazy implicit val system: ActorSystem = ActorSystem("BikeTransitModeSpec", config)

  "The agentsim" must {
    "let persons take bike_transit when their plan says so" in {
      scenario.getPopulation.getPersons.values.asScala
        .foreach(p => PersonTestUtil.putDefaultBeamAttributes(p, BeamMode.allModes))

      scenario.getPopulation.getPersons
        .values()
        .forEach { person =>
          {
            person.getSelectedPlan.getPlanElements.asScala.collect {
              case leg: Leg =>
                leg.setMode("bike_transit")
            }
          }
        }

      val events = mutable.ListBuffer[PersonDepartureEvent]()
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
        new RideHailFleetInitializerProvider(services, beamScenario, scenario),
      )
      mobsim.run()

      assert(events.nonEmpty)
      val bikeTransit = events.filter(event => event.getLegMode.equals("bike_transit"))
      assert(bikeTransit.size > 1)
    }

    "track bike_transit person" in {
      scenario.getPopulation.getPersons.values.asScala
        .foreach(p => PersonTestUtil.putDefaultBeamAttributes(p, BeamMode.allModes))

      scenario.getPopulation.getPersons
        .values()
        .forEach { person =>
          {
            person.getSelectedPlan.getPlanElements.asScala.collect {
              case leg: Leg =>
                leg.setMode("bike_transit")
            }
          }
        }

      val departureEvents = mutable.ListBuffer[PersonDepartureEvent]()
      val arrivalEvents = mutable.ListBuffer[PersonArrivalEvent]()
      val modeChoiceEvent = mutable.ListBuffer[ModeChoiceEvent]()
      services.matsimServices.getEvents.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            event match {
              case event: PersonDepartureEvent if event.getLegMode == "bike_transit" =>
                departureEvents += event
              case event: PersonArrivalEvent if event.getLegMode == "bike_transit" =>
                arrivalEvents += event
              case event: ModeChoiceEvent =>
                modeChoiceEvent += event
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
        new RideHailFleetInitializerProvider(services, beamScenario, scenario),
      )
      mobsim.run()

      assert(departureEvents.nonEmpty)
      assert(arrivalEvents.nonEmpty)

      val departPersons = departureEvents.map(_.getPersonId)
      val arrivedPersons = arrivalEvents.map(_.getPersonId)
      arrivedPersons.foreach(person => {
        Assert.assertTrue(departPersons.contains(person))
        val mustBeBikeTransit = modeChoiceEvent.find(mode => mode.personId == person)
        mustBeBikeTransit match {
          case Some(value) => Assert.assertEquals("bike_transit", value.mode)
          case None        => throw new Exception("Unexpected Mode")
        }
      })
    }
  }
}
