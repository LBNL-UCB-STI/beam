package beam.agentsim.infrastructure

import scala.language.implicitConversions

import beam.utils.FileUtils
import org.matsim.api.core.v01.network.Network
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.NetworkReaderMatsimV2

object NetworkUtilsExtensions {

  def readNetwork(path: String): Network = {
    val network = NetworkUtils.createNetwork
    val reader = new NetworkReaderMatsimV2(network)
    reader.parse(FileUtils.getInputStream(path))
    network
  }

}
