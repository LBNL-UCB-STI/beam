package beam.utils.beam_to_matsim.visualization.via

import beam.utils.beam_to_matsim.events.BeamPathTraversal
import beam.utils.beam_to_matsim.events_filter.{MutablePopulationFilter, MutableSamplingFilter, PopulationSample}
import beam.utils.beam_to_matsim.io.{HashSetReader, Reader, Writer}
import beam.utils.beam_to_matsim.via_event.ViaEvent

import scala.collection.mutable

object visualization_9 extends App {
  val personsInCircleFilePath = "D:/Work/BEAM/visualizations/v2.it20.events.bridge_cap_5000.half_in_SF.persons.txt"
  val personsInCircle = HashSetReader.fromFile(personsInCircleFilePath)

  val beamEventsFilePath = "D:/Work/BEAM/visualizations/v1.it20.events.bridge_cap_5000.csv"
  val sampleSize = 1

  val viaOutputBaseFilePath = "D:/Work/BEAM/visualizations/v9.it20.events.bridge_cap_5000.popSize" + sampleSize
  val viaEventsFile = viaOutputBaseFilePath + ".via.xml"
  val viaIdsFile = viaOutputBaseFilePath + ".ids.txt"
  val viaModesFile = viaOutputBaseFilePath + ".mode.txt"

  val idPrefix = ""

  val filter: MutableSamplingFilter = MutablePopulationFilter(Seq(PopulationSample(0.3, personsInCircle.contains)))

  // val selectedPersons = scala.collection.mutable.HashSet("5637427", "6034392", "5856103")
  // val filter: MutableSamplingFilter = MutablePopulationFilter(Seq(PopulationSample(1, selectedPersons.contains)))

  def vehicleType(pte: BeamPathTraversal): String =
    pte.mode + "_" + pte.vehicleType + "_P%03d".format(pte.numberOfPassengers)

  def vehicleId(pte: BeamPathTraversal): String =
    idPrefix + vehicleType(pte) + "__" + pte.vehicleId

  val (vehiclesEvents, personsEvents) = Reader.readWithFilter(beamEventsFilePath, filter)
  //val (events, typeToId) = EventsProcessor.transformPathTraversals(vehiclesEvents, vehicleId, vehicleType)

  val events = mutable.PriorityQueue.empty[ViaEvent]((e1, e2) => e2.time.compare(e1.time))
  val (modeChoiceEvents, modeToCnt) = Reader.transformModeChoices(personsEvents)
  modeChoiceEvents.foreach(events.enqueue(_))

  Writer.writeViaEventsQueue[ViaEvent](events, _.toXml.toString, viaEventsFile)
  //Writer.writeViaIdFile(typeToId, viaIdsFile)
  Writer.writeViaModes(modeToCnt, viaModesFile)
}
