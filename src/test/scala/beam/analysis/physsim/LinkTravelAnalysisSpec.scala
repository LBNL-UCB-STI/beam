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

  private def computeAngle(source: Coord, destination: Coord): Double = {
    val rad = Math.atan2(destination.getY - source.getY, destination.getX - source.getX)
    println("orig angle : " + rad * 180 / Math.PI)
    val res = if (rad < 0) {
      rad + 3.141593 * 2.0
    } else {
      rad
    }
    println("fin radians : " + res)
    println("fin angle : " + res * 180 / Math.PI)
    res
  }

  def printCond() = {
    println("radians < " + (0.174533 * 180 / Math.PI) + " || radians >=" +  ( 6.10865 * 180 / Math.PI) + "=> R")
    println("radians >=" +  ( 0.17453 * 180 / Math.PI) + "  & radians <" +   ( 1.39626  * 180 / Math.PI) + " => SR") // Soft Right
    println("radians >=" +  ( 1.39626 * 180 / Math.PI) + " & radians <" +    (1.74533   * 180 / Math.PI) + " => S")// Straight
    println("radians >=" +  ( 1.74533 * 180 / Math.PI) + " & radians <" +    (2.96706   * 180 / Math.PI) + " => SL") // Soft Left
    println("radians >=" +  ( 2.96706 * 180 / Math.PI) + " & radians <" +    (3.31613   * 180 / Math.PI) + " => L")// Left
    println("radians >=" +  ( 3.31613 * 180 / Math.PI) + " & radians <" +    (3.32083   * 180 / Math.PI) + " => HL") // Hard Left
    println("radians >=" +  ( 3.32083 * 180 / Math.PI) + " & radians <" +    (6.10865   * 180 / Math.PI) + " => HR")// Hard Right
  }


  "LinkTravelAnalysis" must {
    "generate the required direction to be taken by the vehicle to go to next link" in {

      var currentLinkNodes = new Coord(0,0) -> new Coord(0,1)
      var nextLinkNodes = new Coord(0,1) -> new Coord(1,1)

      computeAngle(vectorFromLinkNodes(currentLinkNodes), vectorFromLinkNodes(nextLinkNodes))
      var direction =
        linkTravelAnalysis.getDirection(vectorFromLinkNodes(currentLinkNodes), vectorFromLinkNodes(nextLinkNodes))
      println(direction)
      //      direction shouldEqual "HR"

      currentLinkNodes = new Coord(0,0) -> new Coord(0,-1)
      nextLinkNodes = new Coord(0,0) -> new Coord(1,0)
      computeAngle(vectorFromLinkNodes(currentLinkNodes), vectorFromLinkNodes(nextLinkNodes))
      direction =
        linkTravelAnalysis.getDirection(vectorFromLinkNodes(currentLinkNodes), vectorFromLinkNodes(nextLinkNodes))
      println(direction)
      //      direction shouldEqual "SR"

      currentLinkNodes = new Coord(0,0) -> new Coord(0,0)
      nextLinkNodes = new Coord(0,0) -> new Coord(1,0)
      computeAngle(vectorFromLinkNodes(currentLinkNodes), vectorFromLinkNodes(nextLinkNodes))
      direction =
        linkTravelAnalysis.getDirection(vectorFromLinkNodes(currentLinkNodes), vectorFromLinkNodes(nextLinkNodes))
      println(direction)
      //      direction shouldEqual "S"

      currentLinkNodes = new Coord(0,0) -> new Coord(1,1)
      nextLinkNodes = new Coord(0,0) -> new Coord(0,0)
      computeAngle(vectorFromLinkNodes(currentLinkNodes), vectorFromLinkNodes(nextLinkNodes))
      direction =
        linkTravelAnalysis.getDirection(vectorFromLinkNodes(currentLinkNodes), vectorFromLinkNodes(nextLinkNodes))
      println(direction)
      //      direction shouldEqual "HL"
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
