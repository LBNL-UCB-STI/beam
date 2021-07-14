package beam.integration

import akka.actor.Status.Failure
import akka.actor._
import akka.testkit.{ImplicitSender, TestActorRef, TestKitBase}
import beam.agentsim.agents.PersonTestUtil
import beam.agentsim.agents.ridehail.{RideHailIterationHistory, RideHailSurgePricingManager}
import beam.integration.AgentsimWithMaximallyBadRouterSpec.BadRouterForTest
import beam.replanning.ModeIterationPlanCleaner
import beam.router.Modes.BeamMode
import beam.router.RouteHistory
import beam.sim.common.GeoUtilsImpl
import beam.sim.{BeamHelper, BeamMobsim, RideHailFleetInitializerProvider}
import beam.utils.SimRunnerForTest
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import org.scalatest._
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.language.postfixOps

class AgentsimWithMaximallyBadRouterSpec
    extends AnyWordSpecLike
    with TestKitBase
    with SimRunnerForTest
    with BadRouterForTest
    with BeamHelper
    with Matchers {

  def config: com.typesafe.config.Config =
    ConfigFactory
      .parseString("""akka.test.timefactor = 10
          |akka.loglevel = off
        """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf").resolve())
      .withValue(
        "beam.agentsim.agents.rideHail.repositioningManager.name",
        ConfigValueFactory.fromAnyRef("DEFAULT_REPOSITIONING_MANAGER")
      )

  def outputDirPath: String = basePath + "/" + testOutputDir + "bad-router-test"

  lazy implicit val system: ActorSystem = ActorSystem("AgentsimWithMaximallyBadRouterSpec", config)

  "The agentsim" must {
    "not get stuck even if the router only throws exceptions" in {
      scenario.getPopulation.getPersons.values
        .forEach(p => PersonTestUtil.putDefaultBeamAttributes(p, BeamMode.allModes))

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
    }
  }
}

object AgentsimWithMaximallyBadRouterSpec {

  trait BadRouterForTest extends BeforeAndAfterAll with ImplicitSender {
    this: Suite with SimRunnerForTest with TestKitBase =>

    var router: ActorRef = _

    override def beforeAll: Unit = {
      super.beforeAll()
      router = TestActorRef(Props(new Actor {
        override def receive: Receive = { case _ =>
          sender ! Failure(new RuntimeException("No idea how to route."))
        }
      }))
      services.beamRouter = router
    }

    override def afterAll(): Unit = {
      router ! PoisonPill
      super.afterAll()
    }

  }
}
