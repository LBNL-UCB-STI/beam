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

      var currentLinkNodes = new Coord(5.332546831852583E7, -176.98930964889) -> new Coord(5.332546831874883E7, 0.0)
      var nextLinkNodes = new Coord(5.932546831879883E7, 160.98930964889) -> new Coord(
        5.932546831879983E7,
        176.98930964889
      )
      var direction =
        linkTravelAnalysis.getDirection(vectorFromLinkNodes(currentLinkNodes), vectorFromLinkNodes(nextLinkNodes))
      direction shouldEqual "HR"

      currentLinkNodes = new Coord(5.337930374504337E7, 35628.816724096985) -> new Coord(
        5.337912416556449E7,
        35628.63669316929
      )
      nextLinkNodes = new Coord(5.337912416556449E7, 35628.63669316929) -> new Coord(
        5.337930374504337E7,
        35628.816724096985
      )
      direction =
        linkTravelAnalysis.getDirection(vectorFromLinkNodes(currentLinkNodes), vectorFromLinkNodes(nextLinkNodes))
      direction shouldEqual "R"

      currentLinkNodes = new Coord(5.337930392370541E7, 35274.30122444436) -> new Coord(
        5.337931276747718E7,
        177.25781739556564
      )
      nextLinkNodes = new Coord(5.336134904264885E7, 35256.481376536256) -> new Coord(
        5.337912434422571E7,
        35274.12298486871
      )
      direction =
        linkTravelAnalysis.getDirection(vectorFromLinkNodes(currentLinkNodes), vectorFromLinkNodes(nextLinkNodes))
      direction shouldEqual "SR"

      currentLinkNodes = new Coord(5.337912434422571E7, 35274.12298486871) -> new Coord(
        5.337930392370541E7,
        35274.30122444436
      )
      nextLinkNodes = new Coord(5.337930392370541E7, 35274.30122444436) -> new Coord(
        5.337912434422571E7,
        35274.12298486871
      )
      direction =
        linkTravelAnalysis.getDirection(vectorFromLinkNodes(currentLinkNodes), vectorFromLinkNodes(nextLinkNodes))
      direction shouldEqual "L"

      currentLinkNodes = new Coord(5.337916983819735E7, 141805.96562165362) -> new Coord(
        5.3379170195297375E7,
        141628.7088840131
      )
      nextLinkNodes = new Coord(5.3361357882119976E7, -177.16827040809167) -> new Coord(
        5.336117854769219E7,
        -177.16737606220184
      )
      direction =
        linkTravelAnalysis.getDirection(vectorFromLinkNodes(currentLinkNodes), vectorFromLinkNodes(nextLinkNodes))
      direction shouldEqual "SL"

      currentLinkNodes = new Coord(5.3343230464354046E7, 177.0778739213648) -> new Coord(
        5.3343230464354046E7,
        -177.0778739213648
      )
      nextLinkNodes = new Coord(5.337930392370541E7, 35274.30122444436) -> new Coord(
        5.337930374504337E7,
        35628.816724096985
      )
      direction =
        linkTravelAnalysis.getDirection(vectorFromLinkNodes(currentLinkNodes), vectorFromLinkNodes(nextLinkNodes))
      direction shouldEqual "S"

      currentLinkNodes = new Coord(551617.508241336, 4183527.671036971) -> new Coord(
        551662.4086659957,
        4183536.2965116384
      )
      nextLinkNodes = new Coord(552233.2751973982, 4182765.140206002) -> new Coord(
        552190.9232867934,
        4182758.1033931947
      )
      direction =
        linkTravelAnalysis.getDirection(vectorFromLinkNodes(currentLinkNodes), vectorFromLinkNodes(nextLinkNodes))
      direction shouldEqual "HL"
    }

    "tell the vehicle to go straight if the current link is the last link" in {
      val currentLinkNodes = new Coord(5.332546831852583E7, -176.98930964889) -> new Coord(5.332546831874883E7, 0.0)
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

  private def vectorFromLink(link: Link): Coord = {
    new Coord(
      link.getToNode.getCoord.getX - link.getFromNode.getCoord.getX,
      link.getToNode.getCoord.getY - link.getFromNode.getCoord.getY
    )
  }

}
