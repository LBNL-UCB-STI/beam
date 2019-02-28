package beam.sflight

import akka.actor.{ActorIdentity, ActorRef, ActorSystem, Identify, PoisonPill}
import akka.testkit.{ImplicitSender, TestKitBase}
import beam.router.BeamRouter
import beam.sim.{BeamServices, BeamServicesImpl}
import beam.utils.{DebugUtil, SimRunnerForTest}
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{Config, ConfigFactory}
import org.matsim.api.core.v01.population.{Activity, Plan}
import org.matsim.core.events.EventsManagerImpl
import org.scalatest._
import org.scalatest.mockito.MockitoSugar

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.language.postfixOps

class AbstractSfLightSpec(val name: String)
    extends SimRunnerForTest
    with TestKitBase
    with WordSpecLike
    with Matchers
    with ImplicitSender
    with MockitoSugar
    with BeforeAndAfterAll
    with DebugUtil {
  lazy implicit val system = ActorSystem(name, ConfigFactory.parseString("""akka.loglevel="OFF"
      |akka.test.timefactor=10""".stripMargin))

  def outputDirPath: String = basePath + "/" + testOutputDir + name
  def config: Config = testConfig("test/input/sf-light/sf-light.conf").resolve()

  lazy val services: BeamServices = new BeamServicesImpl(injector)
  var router: ActorRef = _

  override def beforeAll: Unit = {
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
      )
    )

    within(5 minute) { // Router can take a while to initialize
      router ! Identify(0)
      expectMsgType[ActorIdentity]
    }
  }

  override def afterAll: Unit = {
    router ! PoisonPill
    system.terminate()
  }

  def planToVec(plan: Plan): Vector[Activity] = {
    plan.getPlanElements.asScala
      .filter(_.isInstanceOf[Activity])
      .map(_.asInstanceOf[Activity])
      .toVector
  }
}
