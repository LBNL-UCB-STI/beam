package beam.analysis.physsim

import java.io.File

import beam.analysis.LinkTraversalAnalysis
import beam.sim.BeamServices
import beam.sim.config.MatSimBeamConfigBuilder
import beam.utils.TestConfigUtils
import beam.utils.TestConfigUtils.testConfig
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.{Coord, Scenario}
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.scenario.ScenarioUtils
import org.mockito.Mockito.withSettings
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

class LinkTravelAnalysisSpec extends WordSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  private var linkTravelAnalysis: LinkTraversalAnalysis = _
  private var scenario: Scenario = _

  override def beforeAll(): Unit = {
    val services: BeamServices = mock[BeamServices](withSettings().stubOnly())
    val config = testConfig("test/input/beamville/beam.conf")
    scenario = ScenarioUtils.loadScenario(new MatSimBeamConfigBuilder(config).buildMatSamConf())
    val overwriteExistingFiles =
      OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles
    val BASE_PATH = new File("").getAbsolutePath
    val OUTPUT_DIR_PATH = BASE_PATH + "/" + TestConfigUtils.testOutputDir + "linkstats-test"
    val outputDirectoryHierarchy =
      new OutputDirectoryHierarchy(OUTPUT_DIR_PATH, overwriteExistingFiles)
    linkTravelAnalysis = new LinkTraversalAnalysis(scenario, services, outputDirectoryHierarchy)
  }

  "LinkTravelAnalysis" must {
    "generate the required direction to be taken by the vehicle to go to next link" in {

      var currentLinkNodes = new Coord(0, 0) -> new Coord(0, 1)
      var nextLinkNodes = new Coord(0, 1)    -> new Coord(1, 1)
      var direction =
        linkTravelAnalysis.getDirection(vectorFromLinkNodes(currentLinkNodes), vectorFromLinkNodes(nextLinkNodes))
      direction shouldEqual "R"

      currentLinkNodes = new Coord(0, 0) -> new Coord(-1, 0)
      nextLinkNodes = new Coord(-1, 0)   -> new Coord(0, 1)
      direction =
        linkTravelAnalysis.getDirection(vectorFromLinkNodes(currentLinkNodes), vectorFromLinkNodes(nextLinkNodes))
      direction shouldEqual "HR"

      currentLinkNodes = new Coord(0, 0) -> new Coord(1, 1)
      nextLinkNodes = new Coord(1, 1)    -> new Coord(2, 1)
      direction =
        linkTravelAnalysis.getDirection(vectorFromLinkNodes(currentLinkNodes), vectorFromLinkNodes(nextLinkNodes))
      direction shouldEqual "SR"

      currentLinkNodes = new Coord(0, 0) -> new Coord(-1, 1)
      nextLinkNodes = new Coord(-1, 1)   -> new Coord(-2, 1)
      direction =
        linkTravelAnalysis.getDirection(vectorFromLinkNodes(currentLinkNodes), vectorFromLinkNodes(nextLinkNodes))
      direction shouldEqual "SL"

      currentLinkNodes = new Coord(0, 0) -> new Coord(1, 1)
      nextLinkNodes = new Coord(1, 1)    -> new Coord(0, 2)
      direction =
        linkTravelAnalysis.getDirection(vectorFromLinkNodes(currentLinkNodes), vectorFromLinkNodes(nextLinkNodes))
      direction shouldEqual "HL"

      currentLinkNodes = new Coord(0, 0) -> new Coord(0, 1)
      nextLinkNodes = new Coord(0, 1)    -> new Coord(-1, 1)
      direction =
        linkTravelAnalysis.getDirection(vectorFromLinkNodes(currentLinkNodes), vectorFromLinkNodes(nextLinkNodes))
      direction shouldEqual "L"

    }

    "tell the vehicle to go straight if the current link is the last link" in {
      val currentLinkNodes = new Coord(1, 1) -> new Coord(1, 1)
      val direction =
        linkTravelAnalysis.getDirection(vectorFromLinkNodes(currentLinkNodes), vectorFromLinkNodes(currentLinkNodes))
      direction shouldEqual "S"
    }

    "tell the vehicle to go straight if the direction vector coordinates are unknown or zero" in {
      val currentLinkNodes = new Coord() -> new Coord()
      val direction =
        linkTravelAnalysis.getDirection(vectorFromLinkNodes(currentLinkNodes), vectorFromLinkNodes(currentLinkNodes))
      direction shouldEqual "S"
    }

  }

  private def vectorFromLinkNodes(linkNodes: (Coord, Coord)): Coord = {
    new Coord(
      linkNodes._2.getX - linkNodes._1.getX,
      linkNodes._2.getY - linkNodes._1.getY
    )
  }

}
