package beam.utils

import java.io.{File, PrintWriter}

object NetworkXmlToCSV {

  def main(args: Array[String]): Unit = {
    xmlParser(
      "/home/rajnikant/IdeaProjects/beam/test/input/beamville/physsim-network.xml",
      "/home/rajnikant/IdeaProjects/beam/test/input/beamville/physsim-network.csv",
      "\t"
    )
  }

  def xmlParser(path: String, output: String, delimiter: String): Unit = {
    val nodeHeader = List("@id", "@x", "@y")
    val linkAttribute =
      List("@id", "@from", "@to", "@length", "@freespeed", "@capacity", "@permlanes", "@oneway", "@modes")
    val linkHeader = linkAttribute
      .map(_.replace("@", "link_"))
      .mkString(delimiter) + delimiter + "@attributeOrigId" + delimiter + "@attributeOrigType"

    val writer = new PrintWriter(new File(output))
    val physimElement = scala.xml.XML.loadFile(path)

    writer.write(nodeHeader.map(_.replace("@", "node_")).mkString(delimiter) + "\n")
    (physimElement \ "nodes").foreach { nodes =>
      (nodes \ "node").foreach { node =>
        val row = nodeHeader.map(node \ _).map(_.text).mkString(delimiter)
        writer.write(row + "\n")
      }
    }
    writer.write(linkHeader + "\n")
    (physimElement \ "links").foreach { links =>
      (links \ "link").foreach { link =>
        val row = linkAttribute.map(link \ _).map(_.text).mkString(delimiter) + delimiter +
        (link \ "attributes").map(attributes => (attributes \ "attribute").map(_.text).mkString(delimiter)).mkString
        writer.write(row + "\n")
      }
    }
  }
}
