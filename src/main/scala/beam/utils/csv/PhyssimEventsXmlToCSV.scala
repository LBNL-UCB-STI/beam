package beam.utils.csv

import java.io.{File, PrintWriter}

object PhyssimEventsXmlToCSV {

  def main(args: Array[String]): Unit = {
    assert(args.length == 3)
    val pathToXml = args(0)
    val delimiter = args(1)
    val csvOutputPath = args(2)

    println(s"pathToXml: $pathToXml")
    println(s"delimiter: $delimiter")
    println(s"csvOutputPath: $csvOutputPath")

    println("Starting transformation...")
    eventXmlParser(pathToXml, delimiter, csvOutputPath)
    println("Transformation is done.")
  }

  def eventXmlParser(path: String, delimiter: String, outputPath: String): Unit = {

    val physsimElement = scala.xml.XML.loadFile(path)

    val nodeHeader = (physsimElement \ "event").flatMap(_.attributes.asAttrMap.keys).distinct.sorted
    val nodeWriter = new PrintWriter(new File(outputPath))
    nodeWriter.write(nodeHeader.mkString(delimiter) + "\n")
    (physsimElement \ "event")
      .map(link => nodeHeader.map(header => link \ ("@" + header)).mkString(delimiter) + "\n")
      .foreach(nodeWriter.write)
    nodeWriter.close()
  }
}
