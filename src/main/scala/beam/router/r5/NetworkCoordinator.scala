package beam.router.r5

import java.nio.file.Files.exists
import java.nio.file.Paths

import beam.router.r5.NetworkCoordinator._
import beam.sim.BeamServices
import beam.utils.reflection.ReflectionUtils
import com.conveyal.r5.streets.StreetLayer
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.network.NetworkWriter
import org.matsim.vehicles.Vehicles
import org.slf4j.LoggerFactory

class NetworkCoordinator(val transitVehicles: Vehicles, val beamServices: BeamServices) {

  val log = LoggerFactory.getLogger(classOf[NetworkCoordinator])

  def loadNetwork = {
    if (exists(Paths.get(beamServices.beamConfig.beam.routing.r5.directory, GRAPH_FILE))) {
      log.info(s"Initializing router by reading network from: ${Paths.get(beamServices.beamConfig.beam.routing.r5.directory, GRAPH_FILE).toAbsolutePath}")
      transportNetwork = TransportNetwork.read(Paths.get(beamServices.beamConfig.beam.routing.r5.directory, GRAPH_FILE).toFile)
    } else {  // Need to create the unpruned and pruned networks from directory
      log.info(s"Initializing router by creating network from directory: ${Paths.get(beamServices.beamConfig.beam.routing.r5.directory).toAbsolutePath}")
      transportNetwork = TransportNetwork.fromDirectory(Paths.get(beamServices.beamConfig.beam.routing.r5.directory).toFile, true, false) // Uses the new signature Andrew created
      transportNetwork.write(Paths.get(beamServices.beamConfig.beam.routing.r5.directory, GRAPH_FILE).toFile)
      transportNetwork = TransportNetwork.read(Paths.get(beamServices.beamConfig.beam.routing.r5.directory, GRAPH_FILE).toFile) // Needed because R5 closes DB on write

      log.info(s"Create the MATSim network from R5 network")
      val rmNetBuilder = new R5MnetBuilder(transportNetwork, beamServices.beamConfig.beam.routing.r5.osmMapdbFile)
      rmNetBuilder.buildMNet()
      log.info(s"MATSim network created")
      new NetworkWriter(rmNetBuilder.getNetwork).write(beamServices.beamConfig.matsim.modules.network.inputNetworkFile)
      log.info(s"MATSim network written")
    }
    //
    beamPathBuilder = new BeamPathBuilder(transportNetwork = transportNetwork, beamServices)
    val envelopeInUTM = beamServices.geo.wgs2Utm(transportNetwork.streetLayer.envelope)
    beamServices.geo.utmbbox.maxX = envelopeInUTM.getMaxX + beamServices.beamConfig.beam.spatial.boundingBoxBuffer
    beamServices.geo.utmbbox.maxY = envelopeInUTM.getMaxY + beamServices.beamConfig.beam.spatial.boundingBoxBuffer
    beamServices.geo.utmbbox.minX = envelopeInUTM.getMinX - beamServices.beamConfig.beam.spatial.boundingBoxBuffer
    beamServices.geo.utmbbox.minY = envelopeInUTM.getMinY - beamServices.beamConfig.beam.spatial.boundingBoxBuffer
  }

  private def overrideR5EdgeSearchRadius(newRadius: Double): Unit =
    ReflectionUtils.setFinalField(classOf[StreetLayer], "LINK_RADIUS_METERS", newRadius)

}

object NetworkCoordinator {
  val GRAPH_FILE = "/network.dat"

  var transportNetwork: TransportNetwork = _
  var linkMap: Map[Int, Long] = Map()
  var beamPathBuilder: BeamPathBuilder = _

  def getOsmId(edgeIndex: Int): Long = {
    linkMap.getOrElse(edgeIndex, {
      val osmLinkId = transportNetwork.streetLayer.edgeStore.getCursor(edgeIndex).getOSMID
      linkMap += edgeIndex -> osmLinkId
      osmLinkId
    })
  }
}