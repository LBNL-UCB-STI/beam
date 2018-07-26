package beam.agentsim.agents.household

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import beam.router.r5.NetworkCoordinator
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.core.events.EventsManagerImpl
import org.mockito.Mockito._
import org.scalatest.FunSpecLike
import org.scalatest.mockito.MockitoSugar

class HouseholdActorSpec extends TestKit(ActorSystem("testsystem", ConfigFactory.parseString( """
  akka.loggers = ["akka.testkit.TestEventListener"]
  akka.log-dead-letters = 10
  """).withFallback(testConfig("test/input/beamville/beam.conf")))) with FunSpecLike
  with MockitoSugar with ImplicitSender{

  private implicit val timeout = Timeout(60, TimeUnit.SECONDS)
  val config = BeamConfig(system.settings.config)
  val eventsManager = new EventsManagerImpl()
  val services: BeamServices = {
    val theServices = mock[BeamServices]
    when(theServices.beamConfig).thenReturn(config)
    theServices
  }
  private val networkCoordinator = new NetworkCoordinator(config)
  networkCoordinator.loadNetwork()


}
