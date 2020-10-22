package beam.agentsim.infrastructure.parking

import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.utils.matsim_conversion.ShapeUtils
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.{Link, Network}
import org.matsim.core.utils.collections.QuadTree

import scala.collection.JavaConverters._

object LinkLevelOperations {

  def getLinkTreeMap(network: Network): QuadTree[Link] = {
    val linkCoords = network.getLinks.values().asScala.map(_.getCoord)
    val bounds: ShapeUtils.QuadTreeBounds = ShapeUtils.quadTreeBounds(linkCoords)
    val quadTree = new QuadTree[Link](bounds.minx, bounds.miny, bounds.maxx, bounds.maxy)
    network.getLinks.values().asScala.foreach { link =>
      val middlePoint = link.getCoord
      quadTree.put(middlePoint.getX, middlePoint.getY, link)
    }
    quadTree
  }

  def getLinkIdMapping(network: Network): Map[Id[Link], Link] = network.getLinks.asScala.toMap

  def getLinkToTazMapping(network: Network, tazTreeMap: TAZTreeMap): Map[Link, TAZ] = {
    network.getLinks.values().asScala.map(link => link -> tazTreeMap.getTAZ(link.getCoord)).toMap
  }

  val DefaultLinkId: Id[Link] = Id.createLinkId("default")
  val EmergencyLinkId: Id[Link] = Id.createLinkId("emergency")
}
