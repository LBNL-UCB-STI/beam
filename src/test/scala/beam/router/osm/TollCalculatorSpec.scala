package beam.router.osm

import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import org.scalatest.wordspec.AnyWordSpecLike

import scala.language.postfixOps

//Tolls on osm ids: 79,87,109,147,155,163,1003,1005
class TollCalculatorSpec extends AnyWordSpecLike {
  "Using beamville as input" when {
    val beamvilleTollCalc =
      new TollCalculator(BeamConfig(testConfig("test/input/beamville/beam.conf").resolve()))
    "calculate toll for a single trunk road, it" should {
      "return value $1." in {
        assert(beamvilleTollCalc.calcTollByOsmIds(Vector(109)) == 1.0)
      }
    }

    "calculate toll for a three trunk road, it" should {
      "return value $3." in {
        assert(beamvilleTollCalc.calcTollByOsmIds(Vector(109, 155, 163)) == 3.0)
      }
    }

    "calculate toll for a highway, it" should {
      "return value $6." in {
        assert(beamvilleTollCalc.calcTollByOsmIds(Vector(1003)) == 6.0)
      }
    }

    "calculate toll for a highway and a trunk road, it" should {
      "return value $7." in {
        assert(beamvilleTollCalc.calcTollByOsmIds(Vector(1003, 79)) == 7.0)
      }
    }

    "calculate toll by linkId" should {
      "return 1.0 for tolled link 1" in {
        assert(beamvilleTollCalc.calcTollByLinkId(1, 0) == 1.0)
      }

      "return correct toll based on time range for link 150" in {
        assert(beamvilleTollCalc.calcTollByLinkId(150, 1000) == 0.0)
        assert(beamvilleTollCalc.calcTollByLinkId(150, 3500) == 1.0)
      }

      "return 0.0 for non-tolled link 999" in {
        assert(beamvilleTollCalc.calcTollByLinkId(999, 1000) == 0.0)
      }

      "return 0.0 for negative link ID" in {
        assert(beamvilleTollCalc.calcTollByLinkId(-5, 1000) == 0.0)
      }

      "calculate multi-interval time-varying link tolls" in {
        val tempFile = java.io.File.createTempFile("toll-prices", ".csv")
        tempFile.deleteOnExit()
        java.nio.file.Files.write(
          tempFile.toPath,
          ("linkId,toll,timeRange\n" +
            "101,3.40,[0:21599]\n" +
            "101,4.50,[21600:32400]\n" +
            "101,3.40,[32401:]\n" +
            "102,5.25,[:]\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)
        )
        val config = beam.sim.config.BeamConfig(
          beam.utils.TestConfigUtils
            .testConfig("test/input/beamville/beam.conf")
            .withValue(
              "beam.agentsim.toll.filePath",
              com.typesafe.config.ConfigValueFactory.fromAnyRef(tempFile.getAbsolutePath)
            )
            .resolve()
        )
        val tollCalc = new TollCalculator(config)
        // Morning peak (25000)
        assert(tollCalc.calcTollByLinkId(101, 25000) == 4.50)
        // Off-peak (10000)
        assert(tollCalc.calcTollByLinkId(101, 10000) == 3.40)
        // Flat toll (10000)
        assert(tollCalc.calcTollByLinkId(102, 10000) == 5.25)
        // Untolled link
        assert(tollCalc.calcTollByLinkId(999, 25000) == 0.0)
      }

      "calculate BeamPath tolls properly using calcTollByLinkIds" in {
        val tempFile = java.io.File.createTempFile("toll-prices", ".csv")
        tempFile.deleteOnExit()
        java.nio.file.Files.write(
          tempFile.toPath,
          ("linkId,toll,timeRange\n" +
            "101,3.40,[0:21599]\n" +
            "101,4.50,[21600:32400]\n" +
            "101,3.40,[32401:]\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)
        )
        val config = beam.sim.config.BeamConfig(
          beam.utils.TestConfigUtils
            .testConfig("test/input/beamville/beam.conf")
            .withValue(
              "beam.agentsim.toll.filePath",
              com.typesafe.config.ConfigValueFactory.fromAnyRef(tempFile.getAbsolutePath)
            )
            .resolve()
        )
        val tollCalc = new TollCalculator(config)
        val peakPath = beam.router.model.BeamPath(
          linkIds = Array(100, 101, 200),
          linkTravelTime = Array(10.0, 60.0, 10.0),
          transitStops = None,
          startPoint = beam.agentsim.events.SpaceTime(0.0, 0.0, 25000),
          endPoint = beam.agentsim.events.SpaceTime(0.0, 0.0, 25070),
          distanceInM = 3500.0
        )
        assert(tollCalc.calcTollByLinkIds(peakPath) == 4.50)
      }
    }
  }
}

