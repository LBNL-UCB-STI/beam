package beam.utils.beamToVia

import beam.utils.EventReader
import org.matsim.api.core.v01.events.Event

object EventsReader {
  def fromFile(filePath: String): Option[Traversable[Event]] = {
    val extension = filePath.split('.').lastOption
    extension match {
      case Some("xml") => Some(EventReader.fromXmlFile(filePath))
      case Some("csv") => Some(fromCsv(filePath))
      case _           => None
    }
  }

  private def fromCsv(filePath: String): Array[Event] = {
    val (events, closable) = EventReader.fromCsvFile(filePath, _ => true)
    val eventsArray = events.toArray
    closable.close()

    eventsArray
  }
}
