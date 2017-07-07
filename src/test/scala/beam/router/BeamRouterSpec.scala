package beam.router

import akka.actor.ActorSystem
import akka.testkit.{DefaultTimeout, EventFilter, ImplicitSender, TestKit}
import beam.router.BeamRouter.InitializeRouter
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

/**
  * Created by zeeshan bilal on 7/3/2017.
  */
class BeamRouterSpec extends TestKit(ActorSystem("router-test")) with WordSpecLike
  with ImplicitSender with DefaultTimeout with MockitoSugar with Matchers with BeforeAndAfterAll {

  val services: BeamServices = mock[BeamServices]

  val router = system.actorOf(BeamRouter.props(services))
  override def afterAll = {
    shutdown()
  }

  "On an InitializeRouter message, router" must {
    val router = system.actorOf(BeamRouter.props(services))
    "Respond with RouterInitialized" in {
      router ! InitializeRouter
      expectMsg(InitializeRouter)
    }

    "Respond with RouterInitialized, even on subsequent requests" in {
      router ! InitializeRouter
      expectMsg(InitializeRouter)
      EventFilter.debug(message = "Router already initialized.", occurrences = 1)
    }
  }

  "On some dump message, router" must {
    "ignore the message" in {
      router ! "DUMP"
      expectNoMsg()
      EventFilter.info(start = "Unknown message", occurrences = 1)
    }
  }
}
