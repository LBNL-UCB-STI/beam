package beam.utils.traveltime

import java.util

import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.matsim.core.events.{EventsUtils, MatsimEventsReader}
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.NetworkReaderMatsimV2

object NetworkFeaturesExtractor {
  val delimiter: String = ","

  def main(args: Array[String]): Unit = {
    val pathToEventXml = args(0)
    val pathToNetworkXml = args(1)
    val level = args(2).toInt
    val outputFile = args(3)

    val networkLinks = initializeNetworkLinks(pathToNetworkXml)
    val eventsManager = EventsUtils.createEventsManager()
    val featureExtractor = new LinkInOutFeature(networkLinks, level, outputFile)
    val eventHander = new FeatureEventHandler(networkLinks, delimiter, outputFile, featureExtractor)
    eventsManager.addHandler(eventHander)
    new MatsimEventsReader(eventsManager).readFile(pathToEventXml)

    eventHander.close()
  }

  def initializeNetworkLinks(networkXml: String): util.Map[Id[Link], _ <: Link] = {
    val network = NetworkUtils.createNetwork
    val reader = new NetworkReaderMatsimV2(network)
    reader.readFile(networkXml)
    network.getLinks
  }
}
