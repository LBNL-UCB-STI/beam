package beam.router

import akka.actor.ActorSystem
import beam.sim.BeamHelper
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.{Matchers, WordSpecLike}

import scala.language.postfixOps

class GHRouterSpec extends WordSpecLike with Matchers with BeamHelper {

  lazy val config: Config = ConfigFactory
    .parseString(
      """
         |beam.actorSystemName = "GHRouterSpec"
         |beam.routing.carRouter="staticGH"
      """.stripMargin
    )
    .withFallback(testConfig("test/input/beamville/beam.conf"))
    .resolve()

  lazy implicit val system: ActorSystem = ActorSystem("GHRouterSpec", config)

  "Static GH" must {
    "run successfully" in {
      runBeamWithConfig(config)
    }
  }

}
