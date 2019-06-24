package beam.utils.beamToVia

import java.io.{File, PrintWriter}
import scala.collection.mutable

object EventsWriter {

  def write(
    pathLinkEvents: Traversable[ViaEvent],
    typeToIdSeq: mutable.Map[String, mutable.HashSet[String]],
    outputEventsPath: String
  ): Unit = {
    val pw = new PrintWriter(new File(outputEventsPath))
    pw.println("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<events version=\"1.0\">")
    pathLinkEvents.foreach(event => pw.println(event.toXml.toString()))
    pw.println("</events>")
    pw.close()

    val idsPath = outputEventsPath + ".ids"

    val pw2 = new PrintWriter(new File(idsPath + ".txt"))
    typeToIdSeq.keys.toSeq.sorted.foreach(pw2.println)
    pw2.close()

    import scala.reflect.io.Directory

    val directory = new Directory(new File(idsPath))
    if(!directory.deleteRecursively()) Console.println("Can not delete directory for vehicle ids")
    directory.createDirectory()

    typeToIdSeq.foreach {
      case (vehicleType, ids) =>
        val pw3 = new PrintWriter(new File(idsPath + "\\" + "group." +  vehicleType + ".txt"))
        ids.foreach(pw3.println)
        pw3.close()
    }
  }
}
