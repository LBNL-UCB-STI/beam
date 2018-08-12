package beam.router.r5

import java.nio.file.Files.exists
import java.nio.file.Paths

import beam.sim.config.BeamConfig
import com.conveyal.r5.transit.TransportNetwork
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.network.{Network, NetworkWriter}
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.MatsimNetworkReader

class NetworkCoordinator(beamConfig: BeamConfig) extends LazyLogging {

  var transportNetwork: TransportNetwork = _
  var network: Network = _

  def loadNetwork(): Unit = {
    val GRAPH_FILE = "/network.dat"
    if (exists(Paths.get(beamConfig.beam.routing.r5.directory, GRAPH_FILE))) {
      logger.info(
        s"Initializing router by reading network from: ${Paths.get(beamConfig.beam.routing.r5.directory, GRAPH_FILE).toAbsolutePath}"
      )
      transportNetwork =
        TransportNetwork.read(Paths.get(beamConfig.beam.routing.r5.directory, GRAPH_FILE).toFile)
      network = NetworkUtils.createNetwork()
      new MatsimNetworkReader(network)
        .readFile(beamConfig.matsim.modules.network.inputNetworkFile)
    } else { // Need to create the unpruned and pruned networks from directory
      logger.info(
        s"Initializing router by creating network from directory: ${Paths.get(beamConfig.beam.routing.r5.directory).toAbsolutePath}"
      )
      transportNetwork = TransportNetwork.fromDirectory(
        Paths.get(beamConfig.beam.routing.r5.directory).toFile,
        true,
        false
      ) // Uses the new signature Andrew created
      transportNetwork.write(Paths.get(beamConfig.beam.routing.r5.directory, GRAPH_FILE).toFile)
      transportNetwork = TransportNetwork.read(
        Paths.get(beamConfig.beam.routing.r5.directory, GRAPH_FILE).toFile
      ) // Needed because R5 closes DB on write
      logger.info(s"Create the MATSim network from R5 network")
      val rmNetBuilder = new R5MnetBuilder(transportNetwork, beamConfig)
      rmNetBuilder.buildMNet()
      network = rmNetBuilder.getNetwork
      logger.info(s"MATSim network created")
      new NetworkWriter(network)
        .write(beamConfig.matsim.modules.network.inputNetworkFile)
      logger.info(s"MATSim network written")
    }
  }

}
