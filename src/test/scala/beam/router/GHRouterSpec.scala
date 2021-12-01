package beam.router

import akka.actor.ActorSystem
import beam.sim.BeamHelper
import beam.utils.ParquetReader
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.io.File
import scala.language.postfixOps

class GHRouterSpec extends AnyWordSpecLike with Matchers with BeamHelper {

  lazy val config: Config = ConfigFactory
    .parseString(
      """
         |beam.actorSystemName = "GHRouterSpec"
         |beam.routing.carRouter="staticGH"
         |beam.outputs.writeR5RoutesInterval = 1
      """.stripMargin
    )
    .withFallback(testConfig("test/input/beamville/beam.conf"))
    .resolve()

  lazy val configAltRoutes: Config = ConfigFactory
    .parseString("beam.routing.gh.useAlternativeRoutes = true")
    .withFallback(config)
    .resolve()

  lazy implicit val system: ActorSystem = ActorSystem("GHRouterSpec", config)

  def routingResponseFile(outputDir: String): File = new File(outputDir, "ITERS/it.0/0.routingResponse.parquet")

  def runAndGetRoutesCount(config: Config): Int = {
    val (matsimConfig, _, _) = runBeamWithConfig(config)
    val outputDir = matsimConfig.controler.getOutputDirectory
    val routingResponse = new File(outputDir, "ITERS/it.0/0.routingResponse.parquet")
    val (iterator, closable) = ParquetReader.read(routingResponse.toString)
    val routesCount = iterator.length
    closable.close()
    routesCount
  }

  "Static GH" must {
    "run successfully and provide alternative routes if option is enabled" in {
      val routesCount = runAndGetRoutesCount(config)
      val routesCountAltRoutes = runAndGetRoutesCount(configAltRoutes)
      routesCountAltRoutes should be > routesCount
    }

  }

}
