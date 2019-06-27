package beam.utils.beamToVia

import beam.utils.beamToVia.beamEvent.BeamEvent

object EventsFromPathTraversal extends App {
  //val sourcePath = "D:/Work/BEAM/Via-fs-light/oddBus.xml"
  //val sourcePath = "D:/Work/BEAM/Via-fs-light/2.events.xml"
  //val sourcePath = "D:/Work/BEAM/Via-beamville/0.events.xml"
  val sourcePath = "D:/Work/BEAM/Via-fs-light/2.events.csv"

  val outputEventsPath = sourcePath + ".via.events.xml"

  val events = EventsReader
    .fromFile(sourcePath)
    .getOrElse(Seq.empty[BeamEvent])

  val (pathLinkEvents, typeToIdSeq) = EventsTransformer.transform(events)
  Writer.writeEvents(pathLinkEvents, typeToIdSeq, outputEventsPath)
}
