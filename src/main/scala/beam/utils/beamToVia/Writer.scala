package beam.utils.beamToVia

import java.io.{File, PrintWriter}

import beam.utils.beamToVia.viaEvent.ViaEvent

import scala.collection.mutable

object Writer {

  def writeSeqOfString(script: Traversable[String], outputPath: String): Unit = {
    val pw = new PrintWriter(new File(outputPath))
    script.foreach(pw.println)
    pw.close()
  }

  def writeViaEvents(pathLinkEvents: Traversable[ViaEvent], outputPath: String): Unit = {
    val pw = new PrintWriter(new File(outputPath))
    pw.println("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<events version=\"1.0\">")
    pathLinkEvents.foreach(event => pw.println(event.toXml.toString()))
    pw.println("</events>")
    pw.close()
  }

  def writeViaIdFile(typeToIdSeq: mutable.Map[String, mutable.HashSet[String]], outputPath: String): Unit = {
    val pw2 = new PrintWriter(new File(outputPath))
    typeToIdSeq.map{case (k,v) => k + "     " + v.size}.toSeq.sorted.foreach(pw2.println)
    pw2.close()
  }

  def writeViaIdGroupFiles(typeToIdSeq: mutable.Map[String, mutable.HashSet[String]], outputPath: String): Unit = {
    import scala.reflect.io.Directory

    val directory = new Directory(new File(outputPath))
    if (!directory.deleteRecursively()) Console.println("Can not delete directory for vehicle ids")
    directory.createDirectory()

    typeToIdSeq.foreach {
      case (vehicleType, ids) =>
        val pw3 = new PrintWriter(new File(outputPath + "\\" + "group." + vehicleType + ".txt"))
        ids.foreach(pw3.println)
        pw3.close()
    }
  }
}
