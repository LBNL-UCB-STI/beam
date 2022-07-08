package beam.utils.beam_to_matsim.io

import beam.utils.beam_to_matsim.events.BeamPathTraversal
import beam.utils.beam_to_matsim.events_filter.MutableSamplingFilter

object Utils {

  def buildViaFile(eventsFile: String, outputFile: String, filter: MutableSamplingFilter): Unit = {
    Console.println("reading events with vehicles sampling ...")

    def vehicleType(pte: BeamPathTraversal): String = pte.mode + "__" + pte.vehicleType
    def vehicleId(pte: BeamPathTraversal): String = vehicleType(pte) + "__" + pte.vehicleId

    val (vehiclesEvents, _) = Reader.readWithFilter(eventsFile, filter)
    val (events, typeToId) = Reader.transformPathTraversals(vehiclesEvents, vehicleId, vehicleType)

    Writer.writeViaEventsCollection(events, _.toXmlString, outputFile)
    Writer.writeViaIdFile(typeToId, outputFile + ".ids.txt")
  }

}
