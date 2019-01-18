package beam.utils
import java.util

import com.google.common.collect.Maps
import com.typesafe.scalalogging.LazyLogging
import javax.inject.Inject
import org.apache.commons.collections4.MapUtils
import org.matsim.api.core.v01.network.{Link, Network}

import scala.collection.JavaConverters._

case class LinkWithIndex(link: Link, index: Int)

trait NetworkHelper {
  def totalNumberOfLinks: Int
  def getLinkWithIndex(linkId: String): Option[LinkWithIndex]
  def getLinkId(index: Int): Option[String]
}

class NetworkHelperImpl @Inject()(network: Network) extends NetworkHelper with LazyLogging {
  val s = System.currentTimeMillis()
  private val linkIdWithLink: Array[util.Map.Entry[String, LinkWithIndex]] = network.getLinks
    .entrySet()
    .asScala
    .zipWithIndex
    .map {
      case (x, index) =>
        Maps.immutableEntry[String, LinkWithIndex](x.getKey.toString, LinkWithIndex(x.getValue, index))
    }
    .toArray
  private val linkId2LinkWithIndex: util.Map[String, LinkWithIndex] =
    MapUtils.putAll(new util.HashMap[String, LinkWithIndex](), linkIdWithLink.asInstanceOf[Array[AnyRef]])
  logger.info(
    s"Build linkId2LinkWithIndex with size: ${linkId2LinkWithIndex.size} of type ${linkId2LinkWithIndex.getClass}"
  )

  private val index2LinkId: Array[String] = linkIdWithLink.map(k => k.getKey)
  logger.info(s"Build index2LinkId with size: ${index2LinkId.size}")

  val e = System.currentTimeMillis()
  logger.info(s"NetworkHelperImpl initialized in ${e - s} ms")

  def totalNumberOfLinks: Int = index2LinkId.length

  def getLinkWithIndex(linkId: String): Option[LinkWithIndex] = {
    Option(linkId2LinkWithIndex.get(linkId))
  }

  def getLinkId(index: Int): Option[String] = {
    if (index >= index2LinkId.length || index < 0) {
      logger.warn(s"getLinkId for $index, when index2LinkId length is ${index2LinkId.length}, will return None!")
      None
    } else {
      Option(index2LinkId(index))
    }
  }
}
