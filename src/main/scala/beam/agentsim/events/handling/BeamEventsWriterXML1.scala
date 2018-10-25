package beam.agentsim.events.handling

import java.io.{BufferedWriter, IOException}
import java.util

import beam.sim.BeamServices
import org.matsim.api.core.v01.events.Event
import org.matsim.core.utils.io.UncheckedIOException

import scala.collection.JavaConverters._

class BeamEventsWriterXML1(var out: BufferedWriter,
                           var beamEventLogger: BeamEventsLogger,
                           var beamServices: BeamServices,
                           var eventTypeToLog: Class[_]) extends BeamEventsWriterBase1 {

  override protected def writeEvent(event: Event): Unit = {
    val eventAttributes: util.Map[String, String] = event.getAttributes
    try
      val attrKeys = beamEventLogger.getKeysToWrite(event, eventAttributes)
      val keyValues = attrKeys.asScala map { key =>
        val encodedString = encodeAttributeValue(eventAttributes.getOrDefault(key,""))
        s"$key=\"$encodedString\"\t"
      }
      val eventElem = s"<event ${keyValues.mkString(" ")} />\n"
      this.out.append(eventElem)
  }

  override protected def writeHeader(): Unit = {
    val header =
      """<?xml version="1.0" encoding="utf-8"?>
         <events version="1.0">
      """.stripMargin
    try
      this.out.write(header)
      this.out.write("\n")
    catch {
      case e: IOException =>
        throw new UncheckedIOException(e)
    }
  }

  override def closeFile(): Unit = {
    try {
      this.out.write("</events>")
      this.out.close()
    } catch {
      case e: IOException =>
        throw new UncheckedIOException(e)
    }
  }

  def encodeAttributeValue(attributeValue: String): String = {
    attributeValue.find(List('<','>','\"','&').contains(_)) match {
      case Some(_) =>
        attributeValue
          .replaceAll("<", "&lt;")
          .replaceAll(">", "&gt;")
          .replaceAll("\"", "&quot;")
          .replaceAll("&", "&amp;")
      case None => attributeValue
    }
  }
}
