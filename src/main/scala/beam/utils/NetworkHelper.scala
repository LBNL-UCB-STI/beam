package beam.utils
import beam.utils.ProfilingUtils._
import com.typesafe.scalalogging.LazyLogging
import javax.inject.Inject
import org.matsim.api.core.v01.network.{Link, Network}

import scala.collection.JavaConverters._

trait NetworkHelper {
  def allLinks: Array[Link]
  def maxLinkId: Int
  def getLink(linkId: Int): Option[Link]
  def getLinkUnsafe(linkId: Int): Link
}

class NetworkHelperImpl @Inject()(network: Network) extends NetworkHelper with LazyLogging {
  val (allLinks, maxLinkId) = timed("NetworkHelperImpl init", init(network), x => logger.info(x))

  def getLink(linkId: Int): Option[Link] = Option(getLinkUnsafe(linkId))

  def getLinkUnsafe(linkId: Int): Link = {
    if (linkId >= allLinks.length || linkId < 0) {
      logger.error(s"getLinkUnsafe for $linkId, when allLinks length is ${allLinks.length}!")
      null
    } else {
      allLinks(linkId)
    }
  }

  private[utils] def init(network: Network): (Array[Link], Int) = {
    val allLinks = network.getLinks
      .values()
      .asScala
      .map { link =>
        (link.getId.toString.toInt, link.asInstanceOf[Link])
      }
      .toArray
    val (maxLinkId, link) = allLinks.maxBy { case (linkId, link) => linkId }
    logger.info(s"Total number of links: ${allLinks.length}, MaxLinkId: $maxLinkId.")
    val links = Array.ofDim[Link](maxLinkId + 1)
    allLinks.foreach {
      case (linkId, link) =>
        links(linkId) = link
    }
    (links, maxLinkId)
  }
}
