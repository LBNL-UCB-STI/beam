package beam.router

import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.testkit.TestKit
import akka.util.Timeout
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.Await
import scala.concurrent.duration._

class ClusterWorkerRouterSpec extends AnyWordSpecLike with Matchers {
  implicit val timeout: Timeout = Timeout(60.seconds)

  "ClusterWorkerRouter" should {
    "report ready once the local worker router is initialized" in {
      val config = ConfigFactory
        .parseString("""
            |akka.actor.deployment {
            |  /statsServiceProxy/workerRouter {
            |    router = round-robin-pool
            |    nr-of-instances = 1
            |  }
            |}
            |""".stripMargin)
        .withFallback(testConfig("test/input/beamville/beam.conf"))
        .resolve()

      val system = ActorSystem("ClusterWorkerRouterSpec", config)
      try {
        val workerRouter = system.actorOf(Props(classOf[ClusterWorkerRouter], config), "statsServiceProxy")
        Await.result((workerRouter ? ClusterWorkerRouter.ReadyCheck).mapTo[Boolean], 60.seconds) shouldBe true
      } finally {
        TestKit.shutdownActorSystem(system, 60.seconds, verifySystemShutdown = true)
      }
    }
  }
}
