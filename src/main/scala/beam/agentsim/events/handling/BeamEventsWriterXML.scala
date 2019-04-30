package beam.agentsim.events.handling

import beam.sim.BeamServices
import org.matsim.api.core.v01.events.Event

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

  val specialCharToEscape: Map[Char, String] = Map('<' -> "&lt;", '>' -> "&gt;", '"' -> "&quot;", '&' -> "&amp;")
  val specialChars: Array[Char] = specialCharToEscape.keys.toArray

  val header: String =
    """<?xml version="1.0" encoding="utf-8"?>
<events version="1.0">""".stripMargin

  writeHeaders()

  /**
    * Writes the events to the xml file.
    *
    * @param event event to written
    */
  override protected def writeEvent(event: Event): Unit = {
    //get all the event attributes
    outWriter.append("\t<event ")
    event.getAttributes.forEach((name, value) => {
      outWriter.append(name)
      outWriter.append("=\"")
      outWriter.append(encodeAttributeValue(value))
      outWriter.append("\" ")
    })
    outWriter.append("/>")
    outWriter.newLine()
  }

  /**
    * Adds the xml header to the file.
    */
  override protected def writeHeaders(): Unit = {
    outWriter.write(header)
    outWriter.newLine()
  }

  /**
    * Appends the footer and closes the file stream.
    */
  override def closeFile(): Unit = {
    outWriter.write("</events>")
    outWriter.close()
  }

  /**
    * Encodes any special character encountered in the xml attribute value.
    *
    * @param attributeValue xml attribute value
    * @return encoded attribute value
    */
  private def encodeAttributeValue(attributeValue: String): String = {
    var i: Int = 0
    var result = attributeValue
    while (i < specialChars.length) {
      val char = specialChars(i)
      if (result.indexOf(char) >= 0)
        result = result.replace(char.toString, specialCharToEscape(char))
      i += 1
    }
    result
  }
}
