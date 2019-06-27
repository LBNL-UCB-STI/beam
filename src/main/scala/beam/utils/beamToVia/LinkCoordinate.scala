package beam.utils.beamToVia

import scala.collection.mutable
import scala.xml.{Elem, Node}

case class Point(x: Double, y: Double) {}

object LinkCoordinate {

  def supressNFE[T](func: => T): Option[T] = {
    try {
      Some(func)
    } catch {
      case e: NumberFormatException => None
    }
  }

  def intAttributeValue(node: Node, attributeName: String): Option[Int] =
    node.attribute(attributeName) match {
      case Some(attVal) => supressNFE { attVal.text.toInt }
      case _            => None
    }

  def doubleAttributeValue(node: Node, attributeName: String): Option[Double] =
    node.attribute(attributeName) match {
      case Some(attVal) => supressNFE { attVal.text.toDouble }
      case _            => None
    }

  def parseNetwork(networkXml: Elem, logError: String => Unit = _ => ()): Map[Int, LinkCoordinate] = {
    val nodesMap = parseNodes(networkXml)

    // <link ... to="28921" from="34052" id="91712"...
    val mutableMap = (networkXml \ "links" \ "link")
      .foldLeft(mutable.Map.empty[Int, LinkCoordinate])((map, linkXml) => {
        def getInt(att: String) = intAttributeValue(linkXml, att)

        (getInt("id"), getInt("from"), getInt("to")) match {
          case (Some(id), Some(fromId), Some(toId)) =>
            (nodesMap.get(fromId), nodesMap.get(toId)) match {
              case (Some(from), Some(to)) => map(id) = LinkCoordinate(from, to)
              case _                      => logError("can't find nodes " + fromId + " or " + toId)
            }

          case _ => logError("can't read link from xml: " + linkXml.toString())
        }

        map
      })

    mutableMap.toMap
  }

  def parseNodes(networkXml: Elem, logError: String => Unit = _ => ()): Map[Int, Point] = {
    // <node id="0" x="551370.8722547909" y="4183680.3650971777" ...
    val mutableMap = (networkXml \ "nodes" \ "node")
      .foldLeft(mutable.Map.empty[Int, Point])((map, nodeXml) => {
        def getInt(att: String) = intAttributeValue(nodeXml, att)
        def getDouble(att: String) = doubleAttributeValue(nodeXml, att)

        (getInt("id"), getDouble("x"), getDouble("y")) match {
          case (Some(id), Some(x), Some(y)) => map(id) = Point(x, y)
          case _                            => logError("can't read node from xml: " + nodeXml.toString())
        }

        map
      })

    mutableMap.toMap
  }
}

case class LinkCoordinate(from: Point, to: Point) {}
