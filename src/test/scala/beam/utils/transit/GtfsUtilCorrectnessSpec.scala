package beam.utils.transit

import java.nio.file.{Files, Paths}

import beam.router.r5.DefaultNetworkCoordinator
import beam.sim.config.BeamConfig
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import beam.utils.transit.GtfsFeedAdjuster.GtfsFeedAdjusterConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.{Matchers, WordSpecLike}

/**
  *
  * @author Dmitry Openkov
  */
class GtfsUtilCorrectnessSpec extends WordSpecLike with Matchers {
  val originalDir = Paths.get(getClass.getResource("/r5").getPath)

  "DefaultNetworkCoordinator" should {

    "loads transformed data without errors " in {
      FileUtils.usingTemporaryDirectory { tmpDir =>
        val filesToCopy = List("test.osm.pbf")
        filesToCopy.foreach { name =>
          Files.copy(originalDir.resolve(name), tmpDir.resolve(name))
        }
        val filesToTransform = List("bus.zip", "train.zip")
        filesToTransform.foreach { name =>
          val cfg = GtfsFeedAdjusterConfig(
            "multiplication",
            factor = 2.0,
            in = originalDir.resolve(name),
            out = tmpDir.resolve(name)
          )
          GtfsFeedAdjuster.transformSingleEntry(cfg)
        }

        val config = loadConfig(tmpDir.toString)
        val beamConfig = BeamConfig(config)
        val networkCoordinator = DefaultNetworkCoordinator(beamConfig)
        noException should be thrownBy networkCoordinator.init()
      }
    }
  }

  def loadConfig(beamR5Dir: String) = {
    ConfigFactory
      .parseString(s"""
                      |beam.routing {
                      |  baseDate = "2016-10-17T00:00:00-07:00"
                      |  transitOnStreetNetwork = true
                      |  r5 {
                      |    directory = "$beamR5Dir"
                      |    osmFile = "$beamR5Dir/test.osm.pbf"
                      |    osmMapdbFile = "$beamR5Dir/osm.mapdb"
                      |  }
                      |  startingIterationForTravelTimesMSA = 1
                      |}
                      |beam.physsim.inputNetworkFilePath = "$beamR5Dir/physsim-network.xml"
                      |""".stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()
  }

}
