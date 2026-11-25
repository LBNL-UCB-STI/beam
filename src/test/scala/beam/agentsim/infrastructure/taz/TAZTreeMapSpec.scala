package beam.agentsim.infrastructure.taz

import org.locationtech.jts.geom.{Coordinate, GeometryFactory}
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.Id
import org.matsim.core.network.NetworkUtils
import org.matsim.core.utils.collections.QuadTree
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.JavaConverters._

class TAZTreeMapSpec extends AnyWordSpec with Matchers {

  "TAZTreeMap" should {
    "map walk and bike links for general lookups but keep them out of parking search" in {
      val geometry = new GeometryFactory().createPolygon(
        Array(
          new Coordinate(0, 0),
          new Coordinate(100, 0),
          new Coordinate(100, 100),
          new Coordinate(0, 100),
          new Coordinate(0, 0)
        )
      )
      val taz = new TAZ(Id.create("1", classOf[TAZ]), new Coord(50, 50), 10000, Some(geometry), None)
      val tazQuadTree = new QuadTree[TAZ](0, 0, 100, 100)
      tazQuadTree.put(50, 50, taz)

      val network = NetworkUtils.createNetwork()
      val walkBikeFrom = network.getFactory.createNode(Id.createNodeId("1"), new Coord(40, 50))
      val walkBikeTo = network.getFactory.createNode(Id.createNodeId("2"), new Coord(41, 50))
      val carWalkFrom = network.getFactory.createNode(Id.createNodeId("3"), new Coord(42, 50))
      val carWalkTo = network.getFactory.createNode(Id.createNodeId("4"), new Coord(43, 50))
      val walkBikeLink = network.getFactory.createLink(Id.createLinkId("1"), walkBikeFrom, walkBikeTo)
      val carWalkLink = network.getFactory.createLink(Id.createLinkId("2"), carWalkFrom, carWalkTo)
      walkBikeLink.setAllowedModes(Set("walk", "bike").asJava)
      carWalkLink.setAllowedModes(Set("car", "walk").asJava)
      val links = Map(walkBikeLink.getId -> walkBikeLink, carWalkLink.getId -> carWalkLink)

      val tazTreeMap = new TAZTreeMap(tazQuadTree, "epsg:26910")
      tazTreeMap.mapNetworkToTAZs(links, enableLinkBasedSearch = false)

      tazTreeMap.getTAZfromLink(walkBikeLink.getId) shouldBe Some(taz)
      tazTreeMap.tazToLinkIdMapping(taz.tazId).values().asScala.map(_.getId).toSet shouldBe Set(carWalkLink.getId)
    }
  }
}
