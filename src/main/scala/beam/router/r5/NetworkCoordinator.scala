package beam.router.r5

import java.io.File
import java.nio.file.Files.exists
import java.nio.file.Paths

import akka.actor.{Actor, ActorLogging, Props, Status}
import beam.router.r5.NetworkCoordinator._
import beam.sim.BeamServices
import beam.utils.reflection.ReflectionUtils
import com.conveyal.r5.profile.StreetMode
import com.conveyal.r5.streets.{StreetLayer, TarjanIslandPruner}
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.vehicles.Vehicles

/**
  * Created by salma_000 on 8/25/2017.
  */
class NetworkCoordinator(val transitVehicles: Vehicles, val beamServices: BeamServices) extends Actor with ActorLogging {

  loadNetwork

  // Propagate exceptions to sender
  // Default Akka behavior on an Exception in any Actor does _not_ involve sending _any_ reply.
  // This appears to be by design: A lot goes on in that case (reconfigurable restart strategy, logging, etc.),
  // but sending a reply to the sender of the message which _caused_ the exception must be done explicitly, e.g. like this:
  override def preRestart(reason:Throwable, message:Option[Any]) {
    super.preRestart(reason, message)
    sender() ! Status.Failure(reason)
  }
  // Status.Failure is a special message which causes the Future on the side of the sender to fail,
  // i.e. Await.result re-throws the Exception! In the case of this Actor here, this is a good thing,
  // see the place in BeamSim where the InitTransit message is sent: We want the failure to happen _there_, not _here_.
  //
  // Something like this must be done in every place in the code where one Actor may wait forever for a specific answer.
  // Otherwise, the result is that the default failure mode of our software is to hang, not crash.
  // If we want this particular behavior in several Actors, we can make a trait of it.
  //
  // https://stackoverflow.com/questions/29794454/resolving-akka-futures-from-ask-in-the-event-of-a-failure

  override def receive: Receive = {
    case msg => log.info(s"Unknown message[$msg] received by NetworkCoordinator Actor.")
  }

  def loadNetwork = {
    val networkDir = beamServices.beamConfig.beam.routing.r5.directory
    val networkDirPath = Paths.get(networkDir)
    if (!exists(networkDirPath)) {
      Paths.get(networkDir).toFile.mkdir()
    }

    val unprunedNetworkFilePath = Paths.get(networkDir, UNPRUNED_GRAPH_FILE)  // The first R5 network, created w/out island pruning
    val partiallyPrunedNetworkFile: File = unprunedNetworkFilePath.toFile
    val prunedNetworkFilePath = Paths.get(networkDir, PRUNED_GRAPH_FILE)  // The final R5 network that matches the cleaned (pruned) MATSim network
    val prunedNetworkFile: File = prunedNetworkFilePath.toFile
    if (exists(prunedNetworkFilePath)) {
      log.debug(s"Initializing router by reading network from: ${prunedNetworkFilePath.toAbsolutePath}")
      transportNetwork = TransportNetwork.read(prunedNetworkFile)
    } else {  // Need to create the unpruned and pruned networks from directory
      log.debug(s"Network file [${prunedNetworkFilePath.toAbsolutePath}] not found. ")
      log.debug(s"Initializing router by creating unpruned network from: ${networkDirPath.toAbsolutePath}")
      val partiallyPrunedTransportNetwork = TransportNetwork.fromDirectory(networkDirPath.toFile, false, false) // Uses the new signature Andrew created

      // Prune the walk network. This seems to work without problems in R5.
      new TarjanIslandPruner(partiallyPrunedTransportNetwork.streetLayer, StreetLayer.MIN_SUBGRAPH_SIZE, StreetMode.WALK).run()

      partiallyPrunedTransportNetwork.write(partiallyPrunedNetworkFile)

      ////
      // Convert car network to MATSim network, prune it, compare links one-by-one, and if it was pruned by MATSim,
      // remove the car flag in R5.
      ////
      log.debug(s"Create the cleaned MATSim network from unpuned R5 network")
      val osmFilePath = beamServices.beamConfig.beam.routing.r5.osmFile
      val rmNetBuilder = new R5MnetBuilder(partiallyPrunedNetworkFile.toString, beamServices.beamConfig.beam.routing.r5.osmMapdbFile)
      rmNetBuilder.buildMNet()
      rmNetBuilder.cleanMnet()
      log.debug(s"Pruned MATSim network created and written")
      rmNetBuilder.writeMNet(beamServices.beamConfig.matsim.modules.network.inputNetworkFile)
      log.debug(s"Prune the R5 network")
      rmNetBuilder.pruneR5()
      transportNetwork = rmNetBuilder.getR5Network

      transportNetwork.write(prunedNetworkFile)
      transportNetwork = TransportNetwork.read(prunedNetworkFile) // Needed because R5 closes DB on write
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
  val PRUNED_GRAPH_FILE = "/pruned_network.dat"
  val UNPRUNED_GRAPH_FILE = "/unpruned_network.dat"

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

  def props(transitVehicles: Vehicles, beamServices: BeamServices) = Props(classOf[NetworkCoordinator], transitVehicles, beamServices)
}