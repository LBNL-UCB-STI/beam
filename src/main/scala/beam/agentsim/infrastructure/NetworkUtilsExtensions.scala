package beam.agentsim.infrastructure

import beam.utils.FileUtils
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.network.Network
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.MatsimNetworkReader

import scala.language.implicitConversions

object NetworkUtilsExtensions extends StrictLogging {

  def readNetwork(path: String): Network = {
    val network = NetworkUtils.createNetwork
    val reader = new MatsimNetworkReader(network)
    reader.parse(FileUtils.getInputStream(path))
    network
  }

}
