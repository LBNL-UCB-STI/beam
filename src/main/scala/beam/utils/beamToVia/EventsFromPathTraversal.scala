package beam.utils.beamToVia

import beam.agentsim.events.PathTraversalEvent
import org.matsim.api.core.v01.events.Event
import scala.collection.mutable

object EventsFromPathTraversal extends App {
  //val sourcePath = "D:/Work/BEAM/Via-fs-light/oddBus.xml"
  //val sourcePath = "D:/Work/BEAM/Via-fs-light/2.events.xml"
  val sourcePath = "D:/Work/BEAM/Via-fs-light/2.events.csv"

  val outputEventsPath = sourcePath + ".via.pathLinkEvents.xml"
  val outputGroupsPath = sourcePath + ".via.vehicleTypeToIds.txt"

  val events = EventsReader
    .fromFile(sourcePath)
    .getOrElse(Seq.empty[Event])
    .filter(event => event.getEventType == "PathTraversal")
    .map(PathTraversalEvent.apply)

  val (pathLinkEvents, typeToIdSeq) = PTETransformator.transformMultiple(events)
  EventsWriter.write(pathLinkEvents, typeToIdSeq, outputEventsPath, outputGroupsPath)
}
