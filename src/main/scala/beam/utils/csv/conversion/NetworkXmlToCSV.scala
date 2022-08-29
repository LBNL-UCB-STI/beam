package beam.utils.csv.conversion

import java.io.{BufferedReader, File, PrintWriter}

import org.matsim.core.utils.io.IOUtils

import scala.collection.mutable

object NetworkXmlToCSV {

  def networkXmlParser(
    path: String,
    delimiter: String,
    nodeOutput: String,
    linkOutput: String,
    mergeOutput: String
  ): Unit = {

    val reader: BufferedReader = IOUtils.getBufferedReader(path)
    try {
      val physimElement = scala.xml.XML.load(reader)

      val nodeMap: mutable.Map[String, (_, _)] = mutable.Map()
      val nodeWriter = new PrintWriter(new File(nodeOutput))
      val nodeHeader = List("node_id", "node_x", "node_y")
      nodeWriter.write(nodeHeader.mkString(delimiter) + "\n")
      (physimElement \ "nodes" \ "node").foreach { node =>
        val id = (node \ "@id").text
        val x = (node \ "@x").text
        val y = (node \ "@y").text
        nodeMap += id -> (x, y)
        val row = id + delimiter + x + delimiter + y
        nodeWriter.write(row + "\n")
      }
      nodeWriter.close()

      val linkWriter = new PrintWriter(new File(linkOutput))
      val linkAttribute =
        List("@id", "@from", "@to", "@length", "@freespeed", "@capacity", "@permlanes", "@oneway", "@modes")

      val linkHeader = linkAttribute
        .map(_.replace("@", "link_"))
        .mkString(delimiter) + delimiter + "attributeOrigId" + delimiter + "attributeOrigType"

      linkWriter.write(linkHeader + "\n")
      (physimElement \ "links" \ "link").foreach { link =>
        val row = linkAttribute
          .map(link \ _)
          .map(x => escapeCommaIfNeeded(x.text)) ++ (link \ "attributes" \ "attribute").map(_.text)
        linkWriter.write(row.mkString(delimiter) + "\n")
      }
      linkWriter.close()

      val mergeWriter = new PrintWriter(new File(mergeOutput))
      val mergeHeader = List("from_node", "to_node", "from_x", "from_y", "to_x", "to_y")

      mergeWriter.write(linkHeader + delimiter + mergeHeader.mkString(delimiter) + "\n")

      (physimElement \ "links" \ "link").foreach { link =>
        val from = (link \ "@from").text
        val to = (link \ "@to").text
        val fromCoord = nodeMap(from)
        val toCoord = nodeMap(to)
        val row = new StringBuffer()

        val attr = (link \ "attributes" \ "attribute").map(_.text)
        val linkRow = linkAttribute.map(link \ _).map(x => escapeCommaIfNeeded(x.text)) ++ (if (attr.isEmpty)
                                                                                              Seq("", "")
                                                                                            else attr)

        row
          .append(delimiter)
          .append(from)
          .append(delimiter)
          .append(to)
          .append(delimiter)
          .append(fromCoord._1)
          .append(delimiter)
          .append(fromCoord._2)
          .append(delimiter)
          .append(toCoord._1)
          .append(delimiter)
          .append(toCoord._2)
        mergeWriter.write(linkRow.mkString(delimiter) + row.toString + "\n")

      }

      mergeWriter.close()
    } finally {
      reader.close()
    }
  }

  private def escapeCommaIfNeeded(text: String) = {
    if (text.contains(",")) "\"" + text + "\""
    else text
  }

  def main(args: Array[String]): Unit = {
    // Example of args: `"C:\Users\User\Downloads\physSimNetwork.xml.gz" "," "C:\temp\nodeOutput.csv" "C:\temp\linkOutput.csv" "C:\temp\mergeOutput.csv"`
    assert(args.length == 5)
    val pathToXml = args(0)
    val delimiter = args(1)
    val nodeOutputPath = args(2)
    val linkOutputPath = args(3)
    val mergeOutputPath = args(4)

    println(s"pathToXml: $pathToXml")
    println(s"delimiter: $delimiter")
    println(s"nodeOutputPath: $nodeOutputPath")
    println(s"linkOutputPath: $linkOutputPath")
    println(s"mergeOutputPath: $mergeOutputPath")

    println("Starting transformation...")
    networkXmlParser(pathToXml, delimiter, nodeOutputPath, linkOutputPath, mergeOutputPath)
    println("Transformation is done.")
  }
}
