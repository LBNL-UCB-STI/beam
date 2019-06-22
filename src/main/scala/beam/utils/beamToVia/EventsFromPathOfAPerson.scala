package beam.utils.beamToVia

import beam.agentsim.events.PathTraversalEvent
import org.matsim.api.core.v01.events.Event

import scala.collection.mutable

object EventsFromPathOfAPerson extends App {
  //val sourcePath = "D:/Work/BEAM/Via-fs-light/2.events.xml"
  val sourcePath = "D:/Work/BEAM/Via-fs-light/2.events.csv"

  val outputEventsPath = sourcePath + ".via.pathLinkEvents.xml"
  val outputGroupsPath = sourcePath + ".via.vehicleTypeToIds.txt"

  val events = EventsReader
    .fromFile(sourcePath)
    .getOrElse(Seq.empty[Event])

  //"022802-2012001386215-0-6282252", "060700-2013001017578-0-4879259", "021800-2015000742202-0-4510985",
  val interestingPersons = mutable.HashSet("010900-2016000955704-0-6276349")

  def personIsInterested(personId: String): Boolean = {
    interestingPersons.contains(personId)
  }

  def vehicleIsInterested(vehicleId: String): Boolean = {
    vehicleId.length > 0
    //!vehicleId.startsWith("body-") && vehicleId.length > 0
  }

  val processedEvents = EventsTransformator.filterEvents(events, personIsInterested, vehicleIsInterested)
  val (viaLinkEvents, typeToIdsMap) = EventsTransformator.transform(processedEvents)
  EventsWriter.write(viaLinkEvents, typeToIdsMap, outputEventsPath, outputGroupsPath)
}
