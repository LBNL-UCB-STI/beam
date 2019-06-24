package beam.utils.beamToVia

import org.matsim.api.core.v01.events.Event

object EventsFromPathTraversal extends App {
  //val sourcePath = "D:/Work/BEAM/Via-fs-light/oddBus.xml"
  //val sourcePath = "D:/Work/BEAM/Via-fs-light/2.events.xml"
  val sourcePath = "D:/Work/BEAM/Via-fs-light/2.events.csv"
  //val sourcePath = "D:/Work/BEAM/Via-beamville/0.events.xml"

  val outputEventsPath = sourcePath + ".via.events.xml"

  val events = EventsReader
    .fromFile(sourcePath)
    .getOrElse(Seq.empty[Event])

  val (pathLinkEvents, typeToIdSeq) = EventsTransformator.transform(events)
  EventsWriter.write(pathLinkEvents, typeToIdSeq, outputEventsPath)
}
