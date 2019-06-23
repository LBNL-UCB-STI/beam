package beam.utils.beamToVia

import java.io.{File, PrintWriter}
import scala.collection.mutable

object EventsWriter {

  def write(
             pathLinkEvents: Traversable[ViaEvent],
             typeToIdSeq: mutable.Map[String, mutable.HashSet[String]],
             outputEventsPath: String,
             outputGroupsPath: String
  ): Unit = {
    val strEvents = pathLinkEvents
      .map(pl => pl.toXml.toString())

    val pw = new PrintWriter(new File(outputEventsPath))
    pw.println("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<events version=\"1.0\">")
    strEvents.foreach(event => pw.println(event))
    pw.println("</events>")
    pw.close()

    val pw2 = new PrintWriter(new File(outputGroupsPath))
    pw2.println("all types: ")
    pw2.println("")
    typeToIdSeq.keys.foreach(pw2.println)

    pw2.println("")
    pw2.println("")
    pw2.println("types with ids: ")
    typeToIdSeq.foreach {
      case (vehicleType, ids) =>
        pw2.println("vehicleType =>  " + vehicleType)
        ids.foreach(pw2.println)
    }

    pw2.close()
  }
}
