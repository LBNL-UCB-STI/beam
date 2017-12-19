package beam.router.r5

import java.nio.file.Files.exists
import java.nio.file.Paths

import beam.sim.config.BeamConfig
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.network.{Network, NetworkWriter}
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.MatsimNetworkReader
import org.matsim.vehicles.Vehicles
import org.slf4j.LoggerFactory

class NetworkCoordinator(beamConfig: BeamConfig, val transitVehicles: Vehicles) {

  private val log = LoggerFactory.getLogger(classOf[NetworkCoordinator])
  var transportNetwork: TransportNetwork = _
  var network: Network = _

  def loadNetwork(): Unit = {
    val GRAPH_FILE = "/network.dat"
    if (exists(Paths.get(beamConfig.beam.routing.r5.directory, GRAPH_FILE))) {
      log.info(s"Initializing router by reading network from: ${Paths.get(beamConfig.beam.routing.r5.directory, GRAPH_FILE).toAbsolutePath}")
      transportNetwork = TransportNetwork.read(Paths.get(beamConfig.beam.routing.r5.directory, GRAPH_FILE).toFile)
      network = NetworkUtils.createNetwork()
      new MatsimNetworkReader(network).readFile(beamConfig.matsim.modules.network.inputNetworkFile)
    } else {  // Need to create the unpruned and pruned networks from directory
      log.info(s"Initializing router by creating network from directory: ${Paths.get(beamConfig.beam.routing.r5.directory).toAbsolutePath}")
      transportNetwork = TransportNetwork.fromDirectory(Paths.get(beamConfig.beam.routing.r5.directory).toFile, true, false) // Uses the new signature Andrew created
      transportNetwork.write(Paths.get(beamConfig.beam.routing.r5.directory, GRAPH_FILE).toFile)
      transportNetwork = TransportNetwork.read(Paths.get(beamConfig.beam.routing.r5.directory, GRAPH_FILE).toFile) // Needed because R5 closes DB on write
      log.info(s"Create the MATSim network from R5 network")
      val rmNetBuilder = new R5MnetBuilder(transportNetwork, beamConfig.beam.routing.r5.osmMapdbFile)
      rmNetBuilder.buildMNet()
      network = rmNetBuilder.getNetwork
      log.info(s"MATSim network created")
      new NetworkWriter(network).write(beamConfig.matsim.modules.network.inputNetworkFile)
      log.info(s"MATSim network written")
    }
  }

}
