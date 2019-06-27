package beam.utils.beamToVia

import beam.utils.beamToVia.beamEvent.BeamEvent

object TransformAllPathTraversal {

  def transformAndWrite(runConfig: RunConfig): Unit = {
    val events = EventsReader
      .fromFile(runConfig.beamEventsPath)
      .getOrElse(Seq.empty[BeamEvent])

    val (pathLinkEvents, typeToIdSeq) = EventsTransformer.transform(events)
    Writer.writeViaEvents(pathLinkEvents, runConfig.viaEventsPath)
    Writer.writeViaIdFile(typeToIdSeq, runConfig.viaIdGoupsFilePath)
  }
}
