package beam.utils.traveltime

import java.io.File
import java.util

import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.matsim.core.events.{EventsUtils, MatsimEventsReader}
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.NetworkReaderMatsimV2

object NetworkFeaturesExtractor {

  def main(args: Array[String]): Unit = {
    val pathToEventXml = args(0)
    val pathToNetworkXml = args(1)
    val level = args(2).toInt
    val outputFile = args(3)
    val enteredLinkMultiThreaded = if (args.length > 4) args(4) == "true" else false
    val leavedLinkMultiThreaded = if (args.length > 5) args(5) == "true" else false
    val shouldWriteMapping = if (args.length > 6) args(6) == "true" else false

    val networkLinks = initializeNetworkLinks(pathToNetworkXml)
    val eventsManager = EventsUtils.createEventsManager()
    val writerType = WriterType.Parquet

    val featureExtractor = new LinkInOutFeature(
      links = networkLinks,
      level = level,
      pathToMetadataFolder = new File(outputFile).getParentFile.toString,
      enteredLinkMultiThreaded = enteredLinkMultiThreaded,
      leavedLinkMultiThreaded = leavedLinkMultiThreaded,
      shouldWriteMapping = shouldWriteMapping
    )
    val eventHandler = new FeatureEventHandler(networkLinks, Set(writerType), outputFile, featureExtractor)

    try {
      eventsManager.addHandler(eventHandler)
      new MatsimEventsReader(eventsManager).readFile(pathToEventXml)
    } finally {
      eventHandler.close()
    }
  }

  def initializeNetworkLinks(networkXml: String): util.Map[Id[Link], _ <: Link] = {
    val network = NetworkUtils.createNetwork
    val reader = new NetworkReaderMatsimV2(network)
    reader.readFile(networkXml)
    network.getLinks
  }
}
