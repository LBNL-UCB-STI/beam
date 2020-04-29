package beam.utils.modes.network

import scala.xml.{Node => XmlNode}

case class Network(nodes: List[Node], links: List[Link]) {
  def notAllowedFor(mode: String): Set[Int] = {
    links.filter(_.notAllowedFor(mode)).map(_.id).toSet
  }

  lazy val linksMap = links.groupBy(_.id)
}

trait Identifiable{
  val id: Int
}

case class Node(id: Int, x: Double, y: Double) extends Identifiable

object Node {
  def fromXml(xmlNode: XmlNode): Node = {
    val id = (xmlNode \@ "id").toInt
    val x = (xmlNode \@ "x").toDouble
    val y = (xmlNode \@ "y").toDouble

    Node(id.toInt, x, y)
  }
}

case class Link(id: Int, from: Int, to: Int, modes: Array[String]) extends Identifiable {
  def allowedFor(mode: String) = modes.contains(mode)
  def notAllowedFor(mode: String) = !allowedFor(mode)
}

object Link {
  def fromXml(xmlNode: XmlNode): Link = {
    val id = (xmlNode \@ "id").toInt
    val from = (xmlNode \@ "from").toInt
    val to = (xmlNode \@ "to").toInt
    val modes = (xmlNode \@ "modes").split(",").map(_.trim)

    Link(id, from, to, modes)
  }
}




