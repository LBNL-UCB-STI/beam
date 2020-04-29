package beam.utils.modes

import beam.utils.modes.network.{Identifiable, Link, Network, Node}

import scala.xml.XML

object NetworkReader {

  def readNetwork(networkPath: String): Network = {
    val file = XML.loadFile(networkPath)

    val links = (file \\ "link").map(Link.fromXml).toList
    val nodes = (file \\ "node").map(Node.fromXml).toList

    Network(nodes, links)
  }

  private def toTuple[T <: Identifiable](t: T): Tuple2[Int, T] = {
    t.id -> t
  }
}
