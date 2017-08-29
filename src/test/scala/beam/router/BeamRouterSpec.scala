package beam.router

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{DefaultTimeout, EventFilter, ImplicitSender, TestKit}
import akka.util.Timeout
import beam.router.BeamRouter.{InitializeRouter, RouterInitialized, RouterNeedInitialization, RoutingRequest}
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import com.typesafe.config.ConfigFactory
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers, WordSpecLike}

/**
  * Created by zeeshan bilal on 7/3/2017.
  */
class BeamRouterSpec extends TestKit(ActorSystem("router-test")) with WordSpecLike
  with ImplicitSender with MockitoSugar with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  val TEST_CONFIG: String =
    """
      |routerClass = "beam.router.r5.R5RoutingWorker"
      |baseDate = "2016-10-17T00:00:00-07:00"
      |r5 {
      |  directory = "/model-inputs/r5"
      |  departureWindow = 15
      |}
      |otp {
      |  directory = "/model-inputs/otp"
      |  routerIds = ["sf"]
      |}
      |gtfs {
      |  operatorsFile = "src/main/resources/GTFSOperators.csv"
      |  outputDir = "/gtfs"
      |  apiKey = "ABC123"
      |  crs = "epsg26910"
      |}
    """.stripMargin

  private implicit val timeout = Timeout(60, TimeUnit.SECONDS)

  val services: BeamServices = mock[BeamServices]
  var router: ActorRef = _
  override def beforeAll = {
//    when(services.beamConfig).thenReturn(BeamConfig(null, BeamConfig.Beam(null, "beam", null, null, BeamConfig.Beam.Routing(ConfigFactory.parseString(TEST_CONFIG)), null, null), null, null))
    router = system.actorOf(BeamRouter.props(services))
  }

  override def afterAll = {
    shutdown()
  }

  "On some dump message, router" must {
    "ignore the message" in {
      router ! "DUMP"
      expectNoMsg()
      EventFilter.info(start = "Unknown message", occurrences = 1)
    }
  }

  "On RoutingRequest it" must {
    "Respond RouterNeedInitialization, while uninitialized" in {
      router ! RoutingRequest
      expectMsg(RouterNeedInitialization)
    }
  }

  "On an InitializeRouter message, router" must {

    "Respond with RouterInitialized" in {

      router ! InitializeRouter
      expectMsg(RouterInitialized)
    }

    "Respond with RouterInitialized, even on subsequent requests" in {

      router ! InitializeRouter
      expectMsg(RouterInitialized)
      EventFilter.debug(message = "Router already initialized.", occurrences = 1)
    }
  }


}
