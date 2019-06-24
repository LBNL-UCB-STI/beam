package beam.utils.beamToVia

import org.matsim.api.core.v01.events.Event
import scala.collection.mutable

object EventsFromPathOfAPerson extends App {
  val sourcePath = "D:/Work/BEAM/Via-fs-light/2.events.xml"
  //val sourcePath = "D:/Work/BEAM/Via-fs-light/2.events.csv"
  //val sourcePath = "D:/Work/BEAM/Via-beamville/0.events.xml"

  val outputEventsPath = sourcePath + ".via.trackEvents.xml"

  val events = EventsReader
    .fromFile(sourcePath)
    .getOrElse(Seq.empty[Event])

  //"022802-2012001386215-0-6282252", "060700-2013001017578-0-4879259", "021800-2015000742202-0-4510985",
  val interestingPersons = mutable.HashSet("010900-2016000955704-0-6276349")

  def personIsInterested(personId: String): Boolean = {
    //true
    interestingPersons.contains(personId)
  }

  val processedEvents =
    EventsTransformer.filterAndFixEvents(events, personIsInterested)

  val (viaLinkEvents, typeToIdsMap) = EventsTransformer.transform(processedEvents)
  EventsWriter.write(viaLinkEvents, typeToIdsMap, outputEventsPath)
}
