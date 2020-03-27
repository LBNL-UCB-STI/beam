package beam.utils.beam_to_matsim.visualization.via

import beam.utils.beam_to_matsim.events_filter.{MutablePopulationFilter, MutableSamplingFilter, PopulationSample}
import beam.utils.beam_to_matsim.io.{HashSetReader, Reader, Writer}
import beam.utils.beam_to_matsim.via_event.ViaEvent

import scala.collection.mutable

object visualization_12 extends App {
  val personsInCircleFilePath = "D:/Work/BEAM/visualizations/v2.it20.events.bridge_cap_5000.half_in_SF.persons.txt"
  val personsInCircle = HashSetReader.fromFile(personsInCircleFilePath)

  val beamEventsFilePath = "D:/Work/BEAM/visualizations/v1.it20.events.bridge_cap_5000.csv"
  val sampleSize = 0.5

  val viaOutputBaseFilePath = "D:/Work/BEAM/visualizations/v12.it20.events.bridge_cap_5000.popSize" + sampleSize
  val viaEventsFile = viaOutputBaseFilePath + ".via.xml"
  val viaIdsFile = viaOutputBaseFilePath + ".ids.txt"
  val viaModesFile = viaOutputBaseFilePath + ".activity.txt"

  val idPrefix = ""

  val filter: MutableSamplingFilter = MutablePopulationFilter(Seq(PopulationSample(0.3, personsInCircle.contains)))

  val (vehiclesEvents, personsEvents) = Reader.readWithFilter(beamEventsFilePath, filter)

  val events = mutable.PriorityQueue.empty[ViaEvent]((e1, e2) => e2.time.compare(e1.time))
  val (activities, activityToCnt) = Reader.transformActivities(personsEvents)
  activities.foreach(events.enqueue(_))

  Writer.writeViaEventsQueue[ViaEvent](events, _.toXml.toString, viaEventsFile)
  Writer.writeViaActivities(activityToCnt, viaModesFile)
}
