package scripts.beam_to_matsim.visualization.via

import scripts.beam_to_matsim.events_filter.{MutablePopulationFilter, MutableSamplingFilter, PopulationSample}
import scripts.beam_to_matsim.io.{Reader, Writer}
import scripts.beam_to_matsim.via_event.{ViaEvent, ViaEventsCollection}

import java.io.File
import java.nio.file.Files
import scala.collection.mutable

object visualization_12 extends App {
  val basePath = "D:/Work/beam/NewYork/Runs/calibrated/new-york-200k-baseline__2020-09-01_22-53-55_sfi"

  val beamEventsFilePath = s"${basePath}/10.events.csv"
  val sampleSize = 0.2

  val viaOutputBaseFilePath = s"${basePath}/via_${sampleSize}"
  Files.createDirectory(new File(viaOutputBaseFilePath).toPath)

  val viaEventsFile = viaOutputBaseFilePath + "/via.xml"
  val viaIdsFile = viaOutputBaseFilePath + "/ids.txt"
  val viaModesFile = viaOutputBaseFilePath + "/activity.txt"

  val idPrefix = ""

  val filter: MutableSamplingFilter = MutablePopulationFilter(Seq(PopulationSample(sampleSize, _ => true)))

  val (vehiclesEvents, personsEvents) = Reader.readWithFilter(beamEventsFilePath, filter)

  val eventsCollection = new ViaEventsCollection()
  val (activities, activityToCnt) = Reader.transformActivities(personsEvents)
  activities.foreach(eventsCollection.put)

  Writer.writeViaEventsCollection(eventsCollection, _.toXml.toString, viaEventsFile)
  Writer.writeViaActivities(activityToCnt, viaModesFile)
}
