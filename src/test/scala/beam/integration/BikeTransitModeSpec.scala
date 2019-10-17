package beam.integration

import akka.actor._
import akka.testkit.TestKitBase
import beam.agentsim.agents.PersonTestUtil
import beam.agentsim.agents.ridehail.{RideHailIterationHistory, RideHailSurgePricingManager}
import beam.router.Modes.BeamMode
import beam.router.{BeamSkimmer, RouteHistory, TravelTimeObserved}
import beam.sflight.RouterForTest
import beam.sim.common.GeoUtilsImpl
import beam.sim.{BeamHelper, BeamMobsim}
import beam.utils.SimRunnerForTest
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.events.{ActivityEndEvent, Event, PersonDepartureEvent, PersonEntersVehicleEvent}
import org.matsim.api.core.v01.population.{Activity, Leg}
import org.matsim.core.events.handler.BasicEventHandler
import org.scalatest._

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.language.postfixOps

class BikeTransitModeSpec
    extends WordSpecLike
    with TestKitBase
    with SimRunnerForTest
    with RouterForTest
    with BeamHelper
    with Matchers {

  def config: com.typesafe.config.Config =
    ConfigFactory
      .parseString("""akka.test.timefactor = 10""")
      .withFallback(testConfig("test/input/sf-light/sf-light.conf").resolve())

  def outputDirPath: String = basePath + "/" + testOutputDir + "transit-mode-test"

  lazy implicit val system: ActorSystem = ActorSystem("BikeTransitModeSpec", config)

  "The agentsim" must {
    "let everybody take bike_transit when their plan says so" in {
      scenario.getPopulation.getPersons.values.asScala
        .foreach(p => PersonTestUtil.putDefaultBeamAttributes(p, BeamMode.allModes))
      scenario.getPopulation.getPersons
        .values()
        .forEach { person =>
          person.getSelectedPlan.getPlanElements.asScala.collect {
            case leg: Leg =>
              leg.setMode("bike_transit")
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
        new BeamSkimmer(beamScenario, services.geo),
        new TravelTimeObserved(beamScenario, services.geo),
        new GeoUtilsImpl(services.beamConfig),
        services.networkHelper
      )
      mobsim.run()

      assert(events.nonEmpty)
      var seenEvent = false
      events.foreach {
        case event: PersonDepartureEvent =>
          assert(
            event.getLegMode == "walk" || event.getLegMode == "walk_transit" || event.getLegMode == "be_a_tnc_driver" || event.getLegMode == "be_a_household_cav_driver" || event.getLegMode == "be_a_transit_driver" || event.getLegMode == "cav"
          )
          seenEvent = true
      }
      assert(seenEvent, "Have not seen `PersonDepartureEvent`")
    }
  }
}
