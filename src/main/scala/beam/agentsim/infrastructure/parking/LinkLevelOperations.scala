package beam.agentsim.infrastructure.parking

import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.utils.matsim_conversion.ShapeUtils
import beam.utils.matsim_conversion.ShapeUtils.HasCoord
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.{Link, Network}
import org.matsim.core.utils.collections.QuadTree

import scala.collection.JavaConverters._

object LinkLevelOperations {
  implicit val linkHasCoord: HasCoord[Link] = (a: Link) => a.getCoord

  def getLinkTreeMap(links: Seq[Link]): QuadTree[Link] = {
    ShapeUtils.quadTree(links)
  }

  def getLinkIdMapping(network: Network): Map[Id[Link], Link] = network.getLinks.asScala.toMap

  def getLinkToTazMapping(network: Network, tazTreeMap: TAZTreeMap): Map[Link, TAZ] = {
    network.getLinks.values().asScala.map(link => link -> tazTreeMap.getTAZ(link.getCoord)).toMap
  }

  val DefaultLinkId: Id[Link] = Id.createLinkId("default")
  val EmergencyLinkId: Id[Link] = Id.createLinkId("emergency")
}
