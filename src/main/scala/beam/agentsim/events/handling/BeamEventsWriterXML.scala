package beam.agentsim.events.handling

import java.io.IOException

import beam.sim.BeamServices
import org.matsim.api.core.v01.events.Event
import org.matsim.core.utils.io.UncheckedIOException

import scala.collection.JavaConverters._

/**
  * @author Bhavya Latha Bandaru.
  * Helper class to write BEAM events to an external file.
  * @param outFileName writer instance to write to external file
  * @param beamEventLogger beam events logger
  * @param beamServices beam services
  * @param eventTypeToLog type of event to log
  */
class BeamEventsWriterXML(
  var outFileName: String,
  beamEventLogger: BeamEventsLogger,
  beamServices: BeamServices,
  eventTypeToLog: Class[_]
) extends BeamEventsWriterBase(outFileName, beamEventLogger, beamServices, eventTypeToLog) {

  val specialChars: Array[Char] = Array('<', '>', '\"', '&')

  writeHeaders()

  /**
    * Writes the events to the xml file.
    * @param event event to written
    */
  override protected def writeEvent(event: Event): Unit = {
    //get all the event attributes
    try {
      val keyValues = event.getAttributes.asScala map { keyValue =>
        //for each attribute, encode the values for special characters (if any) and append them to the event xml tag.
        val encodedString = encodeAttributeValue(keyValue._2)
        keyValue._1 + "=\"" + encodedString + "\" "
      }
      //write the event tag to the xml file
      val eventElem = s"\t<event ${keyValues.mkString(" ")}/>\n"
      this.outWriter.append(eventElem)
    } catch {
      case e: Exception =>
        throw e
    }
  }

  /**
    * Adds the xml header to the file.
    */
  override protected def writeHeaders(): Unit = {
    val header =
      """<?xml version="1.0" encoding="utf-8"?>
<events version="1.0">""".stripMargin
    try {
      this.outWriter.write(header)
      this.outWriter.write("\n")
    } catch {
      case e: IOException =>
        throw new UncheckedIOException(e)
    }
  }

  /**
    * Appends the footer and closes the file stream.
    */
  override def closeFile(): Unit = {
    try {
      this.outWriter.write("</events>")
      this.outWriter.close()
    } catch {
      case e: IOException =>
        throw new UncheckedIOException(e)
    }
  }

  /**
    * Encodes any special character encountered in the xml attribute value.
    * @param attributeValue xml attribute value
    * @return encoded attribute value
    */
  private def encodeAttributeValue(attributeValue: String): String = {
    // Replace special characters(if any) with encoded strings
    attributeValue.find(specialChars.contains(_)) match {
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
